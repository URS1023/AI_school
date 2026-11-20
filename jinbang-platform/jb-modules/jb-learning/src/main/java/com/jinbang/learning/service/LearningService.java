package com.jinbang.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.exam.ExamDataApi;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.learning.domain.LnBandSetting;
import com.jinbang.learning.domain.LnKeyQuestion;
import com.jinbang.learning.mapper.LnBandSettingMapper;
import com.jinbang.learning.mapper.LnKeyQuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 作业 / 考试诊断、个人作业详情、班级趋势与需关注学生、讲评、大屏（PRD 6.5、6.6）。 */
@Service
@RequiredArgsConstructor
public class LearningService {
    private final ExamDataApi examData;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;
    private final LnBandSettingMapper bandMapper;
    private final LnKeyQuestionMapper keyMapper;

    public Metrics.Bands bands(String subject) {
        List<LnBandSetting> all = bandMapper.selectList(null);
        LnBandSetting s = all.stream().filter(b -> Objects.equals(b.getSubject(), subject)).findFirst()
                .or(() -> all.stream().filter(b -> b.getSubject() == null).findFirst()).orElse(null);
        return s == null ? Metrics.Bands.DEFAULT : new Metrics.Bands(s.getExcellentRate(), s.getGoodRate(), s.getPassRate(),
                s.getCriticalRange());
    }

    /** 学校自定义分档（分段设置）。 */
    public LnBandSetting saveBands(LnBandSetting s) {
        BizException.throwIf(s.getExcellentRate().compareTo(s.getGoodRate()) <= 0 || s.getGoodRate().compareTo(s.getPassRate()) <= 0
                || s.getPassRate().signum() <= 0 || s.getExcellentRate().compareTo(BigDecimal.valueOf(100)) > 0,
                ErrorCode.BAD_REQUEST, "分档需满足 100 ≥ 优秀 > 良好 > 合格 > 0");
        if (s.getCriticalRange() == null) {
            s.setCriticalRange(BigDecimal.valueOf(5));
        }
        bandMapper.delete(Wrappers.<LnBandSetting>lambdaQuery().eq(s.getSubject() != null, LnBandSetting::getSubject, s.getSubject())
                .isNull(s.getSubject() == null, LnBandSetting::getSubject));
        s.setId(null);
        bandMapper.insert(s);
        return s;
    }

    /** 当前身份在该次作业 / 考试里能看到的班级（R2）。 */
    Set<Long> visibleClasses(ExamDataApi.SourceData d) {
        VisibleScope scope = dataScope.current();
        if (scope.isSchoolWide()) {
            return d.classIds();
        }
        return d.classIds().stream().filter(c -> scope.canSee(c, d.subject())).collect(Collectors.toSet());
    }

    ExamDataApi.SourceData loadVisible(String type, Long id) {
        ExamDataApi.SourceData d = examData.load(type, id);
        if (visibleClasses(d).isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return d;
    }

    public record SourceReport(String title, String subject, LocalDateTime time, Long classId, Metrics.Report report,
                               List<String> weakItems) {
    }

    /** 单次作业 / 考试的数据分析。classId 为空 = 当前身份可见的全部班级（年级视角）。 */
    public SourceReport report(CurrentIdentity ci, String type, Long id, Long classId) {
        ExamDataApi.SourceData d = loadVisible(type, id);
        Set<Long> visible = visibleClasses(d);
        if (classId != null && !visible.contains(classId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        Metrics.Report r = Metrics.compute(d, bands(d.subject()),
                s -> classId == null ? visible.contains(s.classId()) : classId.equals(s.classId()));
        List<String> weak = r.items().stream().filter(i -> i.scoreRate().compareTo(BigDecimal.valueOf(0.6)) < 0)
                .map(Metrics.ItemStat::no).toList();
        return new SourceReport(d.title(), d.subject(), d.time(), classId, r, weak);
    }

    public record TypeLoss(String type, List<String> nos, BigDecimal fullScore, BigDecimal errorRate, BigDecimal myScore,
                           BigDecimal lost) {
    }

    public record StudentDetail(Long studentId, String name, String status, BigDecimal score, BigDecimal rate,
                                Integer classRank, Integer gradeRank, BigDecimal classAvgHundred,
                                Map<String, BigDecimal> itemScores, List<TypeLoss> typeLoss, List<String> easyLost,
                                List<String> advantage) {
    }

    /**
     * 个人作业详情（PRD 6.6 v1.2）：年级 / 班级排名、个人得分率（≤100%）、班均（百分制）、题型失分表（失分 ≥ 0）、
     * 简单丢分题（全班得分率 ≥ 80% 但本人没拿满）、优势得分题（全班得分率 < 60% 但本人拿满）。
     */
    public StudentDetail studentDetail(CurrentIdentity ci, String type, Long id, Long studentId) {
        ExamDataApi.SourceData d = loadVisible(type, id);
        ExamDataApi.StudentResult me = d.students().stream().filter(s -> s.studentId().equals(studentId)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该学生不在本次名单中"));
        if (!visibleClasses(d).contains(me.classId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        Metrics.Report classReport = Metrics.compute(d, bands(d.subject()), s -> s.classId().equals(me.classId()));
        SchoolApi.StudentDTO st = schoolApi.getStudent(studentId);
        if (!"VALID".equals(me.status())) {
            return new StudentDetail(studentId, st.name(), me.status(), null, null, null, null, classReport.avgHundred(),
                    me.itemScores(), List.of(), List.of(), List.of());
        }
        Metrics.StudentRow row = classReport.students().stream().filter(r -> r.studentId().equals(studentId)).findFirst().orElseThrow();
        Map<String, Metrics.ItemStat> itemStats = classReport.items().stream()
                .collect(Collectors.toMap(Metrics.ItemStat::no, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<String, List<ExamDataApi.ItemDef>> byType = d.items().stream()
                .collect(Collectors.groupingBy(ExamDataApi.ItemDef::type, LinkedHashMap::new, Collectors.toList()));
        List<TypeLoss> losses = new ArrayList<>();
        byType.forEach((t, defs) -> {
            BigDecimal full = defs.stream().map(ExamDataApi.ItemDef::fullScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mine = defs.stream().map(x -> Metrics.clamp(me.itemScores().get(x.no()), x.fullScore()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long wrong = defs.stream().filter(x -> Metrics.clamp(me.itemScores().get(x.no()), x.fullScore())
                    .compareTo(x.fullScore()) < 0).count();
            losses.add(new TypeLoss(t, defs.stream().map(ExamDataApi.ItemDef::no).toList(), full,
                    BigDecimal.valueOf(wrong).divide(BigDecimal.valueOf(defs.size()), 4, RoundingMode.HALF_UP), mine,
                    full.subtract(mine).max(BigDecimal.ZERO)));
        });
        List<String> easyLost = new ArrayList<>();
        List<String> advantage = new ArrayList<>();
        for (ExamDataApi.ItemDef it : d.items()) {
            boolean full = Metrics.clamp(me.itemScores().get(it.no()), it.fullScore()).compareTo(it.fullScore()) == 0;
            BigDecimal classRate = itemStats.get(it.no()).scoreRate();
            if (!full && classRate.compareTo(BigDecimal.valueOf(0.8)) >= 0) {
                easyLost.add(it.no());
            }
            if (full && classRate.compareTo(BigDecimal.valueOf(0.6)) < 0) {
                advantage.add(it.no());
            }
        }
        Metrics.Report gradeReport = Metrics.compute(d, bands(d.subject()), s -> true);
        Integer gradeRank = gradeReport.students().stream().filter(r -> r.studentId().equals(studentId))
                .map(Metrics.StudentRow::gradeRank).findFirst().orElse(null);
        return new StudentDetail(studentId, st.name(), me.status(), row.score(), row.rate(), row.classRank(), gradeRank,
                classReport.avgHundred(), me.itemScores(), losses, easyLost, advantage);
    }

    public record TrendPoint(String sourceType, Long sourceId, String title, LocalDateTime time, BigDecimal avgRate,
                             int valid) {
    }

    public record ClassTrend(Long classId, List<TrendPoint> points, List<Long> attention, List<Long> critical) {
    }

    /**
     * 班级学情：历次得分率趋势；需关注学生 = 连续 3 次有效考试名次百分位持续下降（按百分位比较，避免试卷难度差异）+ 最近一次临界生。
     */
    public ClassTrend classTrend(CurrentIdentity ci, Long classId, String subject, LocalDateTime from, LocalDateTime to) {
        VisibleScope scope = dataScope.current();
        if (!scope.isSchoolWide() && !scope.canSee(classId, subject)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        List<TrendPoint> points = new ArrayList<>();
        Map<Long, List<BigDecimal>> percentiles = new LinkedHashMap<>();
        List<Long> critical = new ArrayList<>();
        for (ExamDataApi.SourceBrief b : examData.listSources(Set.of(classId), subject, from, to)) {
            ExamDataApi.SourceData d = examData.load(b.sourceType(), b.sourceId());
            Metrics.Report r = Metrics.compute(d, bands(d.subject()), s -> classId.equals(s.classId()));
            points.add(new TrendPoint(b.sourceType(), b.sourceId(), b.title(), b.time(), r.avgRate(), r.counts().valid()));
            int n = r.students().size();
            critical.clear();
            for (Metrics.StudentRow row : r.students()) {
                BigDecimal pct = n <= 1 ? BigDecimal.ONE : BigDecimal.valueOf(n - row.classRank())
                        .divide(BigDecimal.valueOf(n - 1), 4, RoundingMode.HALF_UP);
                percentiles.computeIfAbsent(row.studentId(), k -> new ArrayList<>()).add(pct);
                if (row.critical()) {
                    critical.add(row.studentId());
                }
            }
        }
        List<Long> attention = new ArrayList<>();
        percentiles.forEach((sid, list) -> {
            if (list.size() >= 3) {
                List<BigDecimal> last = list.subList(list.size() - 3, list.size());
                if (last.get(0).compareTo(last.get(1)) > 0 && last.get(1).compareTo(last.get(2)) > 0) {
                    attention.add(sid);
                }
            }
        });
        Set<Long> union = new HashSet<>(attention);
        union.addAll(critical);
        return new ClassTrend(classId, points, attention, new ArrayList<>(critical));
    }

    // ---------------- 讲评 ----------------

    public record ReviewItem(String no, String type, BigDecimal fullScore, BigDecimal classRate, BigDecimal gradeRate,
                             BigDecimal correctRate, boolean key, Long questionId) {
    }

    /** 讲评：按得分率区间、只看重点题筛选；每题给出班级得分率和年级得分率。 */
    public List<ReviewItem> review(CurrentIdentity ci, String type, Long id, Long classId, BigDecimal minRate,
                                   BigDecimal maxRate, boolean keyOnly) {
        ExamDataApi.SourceData d = loadVisible(type, id);
        Set<Long> visible = visibleClasses(d);
        Metrics.Report cls = Metrics.compute(d, bands(d.subject()),
                s -> classId == null ? visible.contains(s.classId()) : classId.equals(s.classId()));
        Metrics.Report grade = Metrics.compute(d, bands(d.subject()), s -> true);
        Set<String> keys = keyMapper.selectList(Wrappers.<LnKeyQuestion>lambdaQuery().eq(LnKeyQuestion::getSourceType, type)
                .eq(LnKeyQuestion::getSourceId, id)).stream().map(LnKeyQuestion::getItemNo).collect(Collectors.toSet());
        Map<String, Metrics.ItemStat> g = grade.items().stream().collect(Collectors.toMap(Metrics.ItemStat::no, i -> i));
        Map<String, Long> qids = d.items().stream().filter(i -> i.questionId() != null)
                .collect(Collectors.toMap(ExamDataApi.ItemDef::no, ExamDataApi.ItemDef::questionId));
        List<ReviewItem> out = new ArrayList<>();
        for (Metrics.ItemStat i : cls.items()) {
            BigDecimal pct = i.scoreRate().multiply(BigDecimal.valueOf(100));
            if (minRate != null && pct.compareTo(minRate) < 0 || maxRate != null && pct.compareTo(maxRate) > 0) {
                continue;
            }
            if (keyOnly && !keys.contains(i.no())) {
                continue;
            }
            out.add(new ReviewItem(i.no(), i.type(), i.fullScore(), i.scoreRate(), g.get(i.no()).scoreRate(), i.correctRate(),
                    keys.contains(i.no()), qids.get(i.no())));
        }
        return out;
    }

    public void toggleKey(CurrentIdentity ci, String type, Long id, String itemNo, boolean on) {
        loadVisible(type, id);
        if (on) {
            LnKeyQuestion k = new LnKeyQuestion();
            k.setSourceType(type);
            k.setSourceId(id);
            k.setItemNo(itemNo);
            try {
                keyMapper.insert(k);
            } catch (DuplicateKeyException ignored) {
                // 已是重点题
            }
        } else {
            keyMapper.delete(Wrappers.<LnKeyQuestion>lambdaQuery().eq(LnKeyQuestion::getSourceType, type)
                    .eq(LnKeyQuestion::getSourceId, id).eq(LnKeyQuestion::getItemNo, itemNo));
        }
    }

    // ---------------- 大屏 ----------------

    public record SubjectBoard(String subject, int sources, BigDecimal scanRate, BigDecimal scoreRate,
                               BigDecimal markedRate) {
    }

    /** 学校可视化大屏（PRD 6.5）：近 N 天各科扫描率（实交 / 应交）、得分率、主观题批阅率。 */
    public List<SubjectBoard> bigScreen(CurrentIdentity ci, int days) {
        Set<Long> classIds = schoolApi.classesOfCurrentYear(ci.tenantId()).stream().map(SchoolApi.ClassDTO::id)
                .collect(Collectors.toSet());
        Map<String, List<ExamDataApi.SourceData>> bySubject = new LinkedHashMap<>();
        for (ExamDataApi.SourceBrief b : examData.listSources(classIds, null, LocalDateTime.now().minusDays(days), null)) {
            ExamDataApi.SourceData d = examData.load(b.sourceType(), b.sourceId());
            bySubject.computeIfAbsent(d.subject() == null ? "未分科" : d.subject(), k -> new ArrayList<>()).add(d);
        }
        List<SubjectBoard> out = new ArrayList<>();
        bySubject.forEach((subject, list) -> {
            int expected = 0;
            int submitted = 0;
            int finished = 0;
            BigDecimal got = BigDecimal.ZERO;
            BigDecimal should = BigDecimal.ZERO;
            for (ExamDataApi.SourceData d : list) {
                Metrics.Report r = Metrics.compute(d, Metrics.Bands.DEFAULT, s -> true);
                expected += r.counts().total() - r.counts().exempt();
                submitted += r.counts().valid() + r.counts().unfinished();
                finished += r.counts().valid();
                if (r.avgScore() != null) {
                    got = got.add(r.avgScore().multiply(BigDecimal.valueOf(r.counts().valid())));
                    should = should.add(r.fullScore().multiply(BigDecimal.valueOf(r.counts().valid())));
                }
            }
            out.add(new SubjectBoard(subject, list.size(), Metrics.rate(BigDecimal.valueOf(submitted), BigDecimal.valueOf(expected)),
                    Metrics.rate(got, should), Metrics.rate(BigDecimal.valueOf(finished), BigDecimal.valueOf(submitted))));
        });
        return out;
    }
}
