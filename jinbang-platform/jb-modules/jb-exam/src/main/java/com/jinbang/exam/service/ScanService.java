package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.domain.ExScanBatch;
import com.jinbang.exam.domain.ExSheetVersion;
import com.jinbang.exam.mapper.ExAnswerItemMapper;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExScanBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 扫描与识别入库（PRD 6.2、AC-02、AC-03、AC-19）。
 * 识别由 Python 识别服务完成，结果通过回调进入这里：二维码定位答题卡版本、手写学号匹配学生、客观题作答、红笔分数。
 */
@Service
@RequiredArgsConstructor
public class ScanService {
    private final ExScanBatchMapper batchMapper;
    private final ExAnswerMapper answerMapper;
    private final ExAnswerItemMapper itemMapper;
    private final SourceService sourceService;
    private final SheetService sheetService;
    private final WorkbookService workbookService;
    private final ScoringService scoring;
    private final FlowService flowService;
    private final SchoolApi schoolApi;
    private final ApplicationEventPublisher events;
    private final ObjectMapper om;

    /** 识别服务回传的一份答卷（正反面算一份）。 */
    public record RecognizedPage(String pageRef, List<String> images, String qr, String studentNo,
                                 Map<String, String> answers, Map<String, BigDecimal> scores) {
    }

    public record CallbackReq(Long batchId, boolean reRecognize, List<RecognizedPage> pages) {
    }

    public record IngestResult(int recognized, int exceptions, int duplicates, List<String> conflicts) {
    }

    public record BatchStats(int expected, int submitted, int marked, int unsubmitted, int exceptions, int duplicates,
                             int late, int makeup, long images) {
    }

    @Transactional
    public ExScanBatch createBatch(CurrentIdentity ci, String sourceType, Long sourceId, String scanType) {
        SourceService.Source s = sourceService.load(sourceType, sourceId);
        sourceService.checkAccess(ci, s);
        boolean workbook = s.raw() instanceof ExHomework h && "WORKBOOK".equals(h.getScanType());
        BizException.throwIf(s.sheetVersionId() == null && !workbook, ErrorCode.BAD_REQUEST, "请先关联答题卡");
        ExScanBatch b = new ExScanBatch();
        b.setSourceType(sourceType);
        b.setSourceId(sourceId);
        b.setScanType(scanType == null ? "ANSWER_SHEET" : scanType);
        b.setImageCount(0);
        b.setRecognizedCount(0);
        b.setExceptionCount(0);
        b.setBatchStatus("SCANNING");
        b.setScannedBy(ci.userId());
        batchMapper.insert(b);
        flowService.onFirstScan(s);
        return b;
    }

    /** 识别回调是服务间调用，没有登录身份：先跨租户取批次，调用方再按批次的学校设置租户上下文执行 ingest。 */
    public ExScanBatch batchForCallback(Long batchId) {
        return BizException.notNull(TenantContext.ignore(() -> batchMapper.selectById(batchId)), "扫描批次");
    }

    @Transactional
    public IngestResult ingest(ExScanBatch batch, CallbackReq req) {
        SourceService.Source s = sourceService.load(batch.getSourceType(), batch.getSourceId());
        Map<Long, Long> targets = sourceService.targets(s);
        int recognized = 0;
        int exceptions = 0;
        int duplicates = 0;
        int images = 0;
        List<String> conflicts = new ArrayList<>();
        for (RecognizedPage p : req.pages()) {
            images += p.images() == null ? 0 : p.images().size();
            // 教辅：按页面二维码取模板；答题卡：按二维码里的版本号（AC-04），没有二维码用作业绑定的版本
            List<SheetService.Block> blocks = workbookService.blocksByQr(p.qr());
            ExSheetVersion v = null;
            if (blocks == null) {
                v = sheetService.versionByQr(p.qr());
                if (v == null) {
                    BizException.throwIf(s.sheetVersionId() == null, ErrorCode.BAD_REQUEST,
                            "第 " + p.pageRef() + " 页二维码无法识别，且该作业没有关联答题卡");
                    v = sheetService.version(s.sheetVersionId());
                }
                sheetService.lock(v);
                blocks = sheetService.blocks(v);
            }
            if (req.reRecognize()) {
                ExAnswer existing = answerMapper.selectOne(Wrappers.<ExAnswer>lambdaQuery()
                        .eq(ExAnswer::getBatchId, batch.getId()).eq(ExAnswer::getPageRef, p.pageRef())
                        .eq(ExAnswer::getIsCurrent, true));
                if (existing != null) {
                    conflicts.addAll(reRecognize(existing, p));
                    recognized++;
                    continue;
                }
            }
            ExAnswer a = new ExAnswer();
            a.setSourceType(s.type());
            a.setSourceId(s.id());
            a.setSheetVersionId(v == null ? null : v.getId());
            a.setBatchId(batch.getId());
            a.setPageRef(p.pageRef());
            a.setImages(json(p.images()));
            a.setRawStudentNo(p.studentNo());
            a.setIsCurrent(true);
            a.setSubmittedAt(LocalDateTime.now());
            a.setLateFlag(s.deadline() != null && LocalDateTime.now().isAfter(s.deadline()));
            a.setMakeupFlag(!s.isExam() && "MARKED".equals(s.status()));
            a.setAnswerStatus("MARKING");
            // 学号匹配
            Long studentId = null;
            if (p.studentNo() == null || p.studentNo().isBlank()) {
                a.setAnswerStatus("EXCEPTION");
                a.setExceptionReason("STUDENT_MISSING");
            } else {
                SchoolApi.StudentDTO st = schoolApi.findStudentByNo(s.tenantId(), p.studentNo().trim()).orElse(null);
                if (st == null) {
                    a.setAnswerStatus("EXCEPTION");
                    a.setExceptionReason("STUDENT_NOT_FOUND");
                } else if (!targets.containsKey(st.id())) {
                    a.setAnswerStatus("EXCEPTION");
                    a.setExceptionReason("NOT_IN_LIST");
                    a.setStudentId(st.id());
                } else {
                    studentId = st.id();
                }
            }
            if (Boolean.TRUE.equals(a.getLateFlag()) && !s.allowLate() && studentId != null) {
                a.setAnswerStatus("EXCEPTION");
                a.setExceptionReason("LATE_NOT_ALLOWED");
            }
            if (studentId != null && !"EXCEPTION".equals(a.getAnswerStatus())) {
                a.setStudentId(studentId);
                a.setClassId(targets.get(studentId));
            }
            answerMapper.insert(a);
            createItems(a, blocks, p);
            if ("EXCEPTION".equals(a.getAnswerStatus())) {
                exceptions++;
                scoring.recalc(a);
                continue;
            }
            recognized++;
            if (attachToStudent(a)) {
                duplicates++;
            }
        }
        batch.setImageCount(batch.getImageCount() + images);
        batch.setRecognizedCount(batch.getRecognizedCount() + recognized);
        batch.setExceptionCount(batch.getExceptionCount() + exceptions);
        batchMapper.updateById(batch);
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        return new IngestResult(recognized, exceptions, duplicates, conflicts);
    }

    private void createItems(ExAnswer a, List<SheetService.Block> blocks, RecognizedPage p) {
        for (SheetService.Block b : blocks) {
            ExAnswerItem it = new ExAnswerItem();
            it.setAnswerId(a.getId());
            it.setItemNo(b.no());
            it.setItemType(b.type());
            it.setObjective(b.objective());
            it.setFullScore(b.fullScore());
            it.setStdAnswer(b.answer());
            it.setStudentAnswer(lookup(p.answers(), b.no()));
            it.setNeedsReview(false);
            if (b.objective()) {
                it.setScore(ScoringService.autoScore(it));
                it.setScoreSource("AUTO");
            } else if (lookup(p.scores(), b.no()) != null) {
                scoring.applyRecognizedScore(it, lookup(p.scores(), b.no()));
            }
            itemMapper.insert(it);
        }
    }

    /**
     * 把答卷挂到学生名下。已有当前答卷（重复扫描，AC-02）：默认保留最新一份，旧的进「重复扫描」列表；
     * 旧卷上人工批阅过的分数搬到新卷，不被识别结果覆盖。返回是否发生了重复。
     */
    private boolean attachToStudent(ExAnswer a) {
        // 教辅作业一个学生有多页，重复判断按「同一页」；答题卡按「同一份作业」
        String page = pageOf(a);
        ExAnswer old = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, a.getSourceType())
                        .eq(ExAnswer::getSourceId, a.getSourceId()).eq(ExAnswer::getStudentId, a.getStudentId())
                        .eq(ExAnswer::getIsCurrent, true).ne(ExAnswer::getId, a.getId()).ne(ExAnswer::getAnswerStatus, "EXCEPTION"))
                .stream().filter(o -> Objects.equals(pageOf(o), page)).findFirst().orElse(null);
        if (old != null) {
            Map<String, ExAnswerItem> oldItems = new HashMap<>();
            scoring.items(old.getId()).forEach(i -> oldItems.put(i.getItemNo(), i));
            for (ExAnswerItem ni : scoring.items(a.getId())) {
                ExAnswerItem oi = oldItems.get(ni.getItemNo());
                if (oi != null && ScoringService.MANUAL_SOURCES.contains(oi.getScoreSource())) {
                    ni.setScore(oi.getScore());
                    ni.setScoreSource(oi.getScoreSource());
                    ni.setMarkedBy(oi.getMarkedBy());
                    ni.setMarkedAt(oi.getMarkedAt());
                    ni.setNeedsReview(false);
                    ni.setReviewReason(null);
                    itemMapper.updateById(ni);
                }
            }
            old.setIsCurrent(false);
            old.setAnswerStatus("REPLACED");
            old.setReplacedBy(a.getId());
            answerMapper.updateById(old);
        }
        scoring.recalc(a);
        return old != null;
    }

    /** 整批重新识别（AC-03）：只更新客观题和未人工批阅的题；人工分与新识别分不同 → 记为冲突提示教师。 */
    private List<String> reRecognize(ExAnswer a, RecognizedPage p) {
        List<String> conflicts = new ArrayList<>();
        for (ExAnswerItem it : scoring.items(a.getId())) {
            String newAns = lookup(p.answers(), it.getItemNo());
            BigDecimal newScore = lookup(p.scores(), it.getItemNo());
            if (Boolean.TRUE.equals(it.getObjective())) {
                if (newAns != null) {
                    it.setStudentAnswer(newAns);
                    it.setScore(ScoringService.autoScore(it));
                    it.setScoreSource("AUTO");
                }
            } else if (ScoringService.MANUAL_SOURCES.contains(it.getScoreSource())) {
                if (newScore != null && it.getScore() != null && newScore.compareTo(it.getScore()) != 0) {
                    conflicts.add("学号 " + a.getRawStudentNo() + " 第 " + it.getItemNo() + " 题：人工分 "
                            + it.getScore().stripTrailingZeros().toPlainString() + "，重新识别为 "
                            + newScore.stripTrailingZeros().toPlainString() + "，已保留人工分，请确认");
                }
            } else {
                if (newAns != null) {
                    it.setStudentAnswer(newAns);
                }
                if (newScore != null) {
                    scoring.applyRecognizedScore(it, newScore);
                }
            }
            itemMapper.updateById(it);
        }
        scoring.recalc(a);
        return conflicts;
    }

    // ---------------- 异常卷 / 重复卷 / 重新分配 ----------------

    public List<ExAnswer> exceptions(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        return answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, type)
                .eq(ExAnswer::getSourceId, id).eq(ExAnswer::getAnswerStatus, "EXCEPTION").eq(ExAnswer::getIsCurrent, true)
                .orderByAsc(ExAnswer::getPageRef));
    }

    public List<ExAnswer> duplicates(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        return answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, type)
                .eq(ExAnswer::getSourceId, id).eq(ExAnswer::getAnswerStatus, "REPLACED").orderByDesc(ExAnswer::getUpdatedAt));
    }

    /**
     * 「分配给该考生」/「重新分配」（AC-19）：异常卷或已归属的卷改归指定学生；
     * 已提交 / 未提交人数和报表随事件重算；操作留痕。
     */
    @Transactional
    @OperLog(module = "阅卷-扫描", action = "答卷分配给学生")
    public ExAnswer assign(CurrentIdentity ci, Long answerId, Long studentId) {
        ExAnswer a = BizException.notNull(answerMapper.selectById(answerId), "答卷");
        SourceService.Source s = sourceService.load(a.getSourceType(), a.getSourceId());
        sourceService.checkAccess(ci, s);
        Map<Long, Long> targets = sourceService.targets(s);
        BizException.throwIf(!targets.containsKey(studentId), ErrorCode.BAD_REQUEST, "该学生不在本次作业 / 考试的名单中");
        a.setStudentId(studentId);
        a.setClassId(targets.get(studentId));
        a.setExceptionReason(null);
        a.setAnswerStatus("MARKING");
        a.setIsCurrent(true);
        answerMapper.updateById(a);
        attachToStudent(a);
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        return answerMapper.selectById(answerId);
    }

    /** 重复扫描里手动换回旧的一份。 */
    @Transactional
    @OperLog(module = "阅卷-扫描", action = "换回旧答卷")
    public ExAnswer restore(CurrentIdentity ci, Long oldAnswerId) {
        ExAnswer old = BizException.notNull(answerMapper.selectById(oldAnswerId), "答卷");
        BizException.throwIf(!"REPLACED".equals(old.getAnswerStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该答卷不在重复扫描列表中");
        SourceService.Source s = sourceService.load(old.getSourceType(), old.getSourceId());
        sourceService.checkAccess(ci, s);
        ExAnswer cur = answerMapper.selectOne(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, old.getSourceType())
                .eq(ExAnswer::getSourceId, old.getSourceId()).eq(ExAnswer::getStudentId, old.getStudentId())
                .eq(ExAnswer::getIsCurrent, true));
        if (cur != null) {
            cur.setIsCurrent(false);
            cur.setAnswerStatus("REPLACED");
            cur.setReplacedBy(old.getId());
            answerMapper.updateById(cur);
        }
        old.setIsCurrent(true);
        old.setReplacedBy(null);
        old.setAnswerStatus("MARKING");
        answerMapper.updateById(old);
        scoring.recalc(old);
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        return old;
    }

    /** 作业列表卡片 / 扫描统计：应交、实交、已批阅、未交、异常、重复、迟交、补交。 */
    public BatchStats stats(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        return statsOf(s);
    }

    public BatchStats statsOf(SourceService.Source s) {
        Map<Long, Long> targets = sourceService.targets(s);
        List<ExAnswer> all = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, s.type())
                .eq(ExAnswer::getSourceId, s.id()));
        List<ExAnswer> current = all.stream().filter(a -> Boolean.TRUE.equals(a.getIsCurrent())
                && !"EXCEPTION".equals(a.getAnswerStatus()) && a.getStudentId() != null && targets.containsKey(a.getStudentId())).toList();
        // 教辅作业一个学生可能有多页答卷：按学生去重；一个学生的所有页都阅完才算「已批阅」
        Map<Long, List<ExAnswer>> byStudent = current.stream().collect(java.util.stream.Collectors.groupingBy(ExAnswer::getStudentId));
        int marked = (int) byStudent.values().stream().filter(l -> l.stream().allMatch(a -> "MARKED".equals(a.getAnswerStatus()))).count();
        long images = batchMapper.selectList(Wrappers.<ExScanBatch>lambdaQuery().eq(ExScanBatch::getSourceType, s.type())
                .eq(ExScanBatch::getSourceId, s.id())).stream().mapToLong(ExScanBatch::getImageCount).sum();
        return new BatchStats(targets.size(), byStudent.size(), marked, targets.size() - byStudent.size(),
                (int) all.stream().filter(a -> "EXCEPTION".equals(a.getAnswerStatus()) && Boolean.TRUE.equals(a.getIsCurrent())).count(),
                (int) all.stream().filter(a -> "REPLACED".equals(a.getAnswerStatus())).count(),
                (int) current.stream().filter(a -> Boolean.TRUE.equals(a.getLateFlag())).count(),
                (int) current.stream().filter(a -> Boolean.TRUE.equals(a.getMakeupFlag())).count(), images);
    }

    /** 未提交学生名单。 */
    public List<Long> unsubmitted(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        Map<Long, Long> targets = sourceService.targets(s);
        List<Long> submitted = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, type)
                .eq(ExAnswer::getSourceId, id).eq(ExAnswer::getIsCurrent, true).ne(ExAnswer::getAnswerStatus, "EXCEPTION"))
                .stream().map(ExAnswer::getStudentId).filter(Objects::nonNull).toList();
        return targets.keySet().stream().filter(sid -> !submitted.contains(sid)).toList();
    }

    /** 识别结果按题号取值：先按完整题号；教辅题号带页码前缀（12-3），识别服务回传的是页内题号（3）。 */
    static <T> T lookup(Map<String, T> m, String no) {
        if (m == null || no == null) {
            return null;
        }
        T v = m.get(no);
        if (v == null && no.contains("-")) {
            v = m.get(no.substring(no.indexOf('-') + 1));
        }
        return v;
    }

    /** 教辅答卷的页码（题号形如 12-3）；答题卡答卷返回 null。 */
    private String pageOf(ExAnswer a) {
        if (a.getSheetVersionId() != null) {
            return null;
        }
        return scoring.items(a.getId()).stream().map(ExAnswerItem::getItemNo).filter(n -> n.contains("-"))
                .map(n -> n.substring(0, n.indexOf('-'))).findFirst().orElse(null);
    }

    private String json(Object o) {
        try {
            return o == null ? null : om.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
