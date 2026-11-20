package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.api.ai.AiApi;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExMarkingAssign;
import com.jinbang.exam.domain.ExProblemPaper;
import com.jinbang.exam.mapper.ExAnswerItemMapper;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExMarkingAssignMapper;
import com.jinbang.exam.mapper.ExProblemPaperMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 智能阅卷（PRD 6.4）：按题批阅进度、下一份、提交 / 回批、客观题矩阵纠错、问题卷、作文 AI 批阅。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingService {
    private final ExAnswerMapper answerMapper;
    private final ExAnswerItemMapper itemMapper;
    private final ExMarkingAssignMapper assignMapper;
    private final ExProblemPaperMapper problemMapper;
    private final SourceService sourceService;
    private final ScoringService scoring;
    private final FlowService flowService;
    private final AiApi aiApi;
    private final ObjectMapper om;
    private final ApplicationEventPublisher events;

    public record QuestionProgress(String itemNo, String type, boolean objective, BigDecimal fullScore, int total,
                                   int marked, BigDecimal avg, int needsReview) {
    }

    public record NextItem(Long answerId, Long studentId, String itemNo, BigDecimal fullScore, String studentAnswer,
                           String images, BigDecimal currentScore, String reviewReason, int remaining) {
    }

    private List<ExAnswer> currentAnswers(SourceService.Source s) {
        return answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, s.type())
                .eq(ExAnswer::getSourceId, s.id()).eq(ExAnswer::getIsCurrent, true)
                .in(ExAnswer::getAnswerStatus, "MARKING", "MARKED").orderByAsc(ExAnswer::getId));
    }

    /** 按题批阅视图：题号、批阅进度（如 0/39）、平均得分、本题满分。 */
    public List<QuestionProgress> progress(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        List<ExAnswer> answers = currentAnswers(s);
        Map<String, List<ExAnswerItem>> byNo = new LinkedHashMap<>();
        for (ExAnswer a : answers) {
            scoring.items(a.getId()).forEach(it -> byNo.computeIfAbsent(it.getItemNo(), k -> new ArrayList<>()).add(it));
        }
        List<QuestionProgress> out = new ArrayList<>();
        byNo.forEach((no, items) -> {
            List<ExAnswerItem> marked = items.stream().filter(i -> i.getScore() != null && !Boolean.TRUE.equals(i.getNeedsReview())).toList();
            BigDecimal sum = marked.stream().map(ExAnswerItem::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            ExAnswerItem f = items.getFirst();
            out.add(new QuestionProgress(no, f.getItemType(), Boolean.TRUE.equals(f.getObjective()), f.getFullScore(), items.size(),
                    marked.size(), marked.isEmpty() ? null : sum.divide(BigDecimal.valueOf(marked.size()), 2, RoundingMode.HALF_UP),
                    (int) items.stream().filter(i -> Boolean.TRUE.equals(i.getNeedsReview())).count()));
        });
        return out;
    }

    /** 下一份待批：按题连续批阅（同一题所有学生）。 */
    public NextItem next(CurrentIdentity ci, String type, Long id, String itemNo) {
        SourceService.Source s = sourceService.load(type, id);
        checkMarker(ci, s, itemNo);
        checkNotPaused(s);
        List<ExAnswer> answers = currentAnswers(s);
        int remaining = 0;
        NextItem first = null;
        for (ExAnswer a : answers) {
            ExAnswerItem it = item(a.getId(), itemNo);
            if (it != null && (it.getScore() == null || Boolean.TRUE.equals(it.getNeedsReview()))) {
                remaining++;
                if (first == null) {
                    first = new NextItem(a.getId(), a.getStudentId(), itemNo, it.getFullScore(), it.getStudentAnswer(),
                            a.getImages(), it.getScore(), it.getReviewReason(), 0);
                }
            }
        }
        if (first == null) {
            return null;
        }
        return new NextItem(first.answerId(), first.studentId(), itemNo, first.fullScore(), first.studentAnswer(),
                first.images(), first.currentScore(), first.reviewReason(), remaining);
    }

    /**
     * 提交 / 回批分数（AC-18：越界拒绝）。发布后改分 = 成绩更正：必须写原因，自动通知学生和家长（AC-05）。
     */
    @Transactional
    @OperLog(module = "阅卷", action = "提交分数")
    public ExAnswer submit(CurrentIdentity ci, Long answerId, String itemNo, BigDecimal score, String reason) {
        ExAnswer a = BizException.notNull(answerMapper.selectById(answerId), "答卷");
        BizException.throwIf(!Boolean.TRUE.equals(a.getIsCurrent()) || "EXCEPTION".equals(a.getAnswerStatus()),
                ErrorCode.STATE_TRANSITION_DENIED, "该答卷不是有效答卷（异常卷请先分配学生）");
        SourceService.Source s = sourceService.load(a.getSourceType(), a.getSourceId());
        checkMarker(ci, s, itemNo);
        checkNotPaused(s);
        ExAnswerItem it = BizException.notNull(item(answerId, itemNo), "题目");
        boolean afterPublish = sourceService.publishedFor(s, a.getClassId());
        BizException.throwIf(afterPublish && (reason == null || reason.isBlank()), ErrorCode.BAD_REQUEST,
                "成绩已发布，修改成绩必须填写更正原因");
        scoring.setScore(a, it, score, "MANUAL", ci.userId(), reason, afterPublish);
        if (afterPublish) {
            flowService.notifyCorrections(s, answerId);
        } else {
            events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        }
        return answerMapper.selectById(answerId);
    }

    /** 客观题成绩矩阵里修改「学生作答」（纠正识别错误）后重新判分。 */
    @Transactional
    @OperLog(module = "阅卷", action = "修改客观题作答")
    public ExAnswer correctObjective(CurrentIdentity ci, Long answerId, String itemNo, String studentAnswer) {
        ExAnswer a = BizException.notNull(answerMapper.selectById(answerId), "答卷");
        SourceService.Source s = sourceService.load(a.getSourceType(), a.getSourceId());
        sourceService.checkAccess(ci, s);
        ExAnswerItem it = BizException.notNull(item(answerId, itemNo), "题目");
        BizException.throwIf(!Boolean.TRUE.equals(it.getObjective()), ErrorCode.BAD_REQUEST, "只有客观题可以修改作答");
        it.setStudentAnswer(studentAnswer);
        boolean afterPublish = sourceService.publishedFor(s, a.getClassId());
        scoring.setScore(a, it, ScoringService.autoScore(it), "MANUAL", ci.userId(), "修正识别作答", afterPublish);
        if (afterPublish) {
            flowService.notifyCorrections(s, answerId);
        } else {
            events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        }
        return answerMapper.selectById(answerId);
    }

    /** 客观题成绩矩阵：学生 × 题号。 */
    public List<Map<String, Object>> objectiveMatrix(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExAnswer a : currentAnswers(s)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("answerId", a.getId());
            row.put("studentId", a.getStudentId());
            row.put("total", a.getTotalScore());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (ExAnswerItem it : scoring.items(a.getId())) {
                if (Boolean.TRUE.equals(it.getObjective())) {
                    cells.add(Map.of("no", it.getItemNo(), "answer", it.getStudentAnswer() == null ? "" : it.getStudentAnswer(),
                            "correct", it.getScore() != null && it.getScore().compareTo(it.getFullScore()) == 0));
                }
            }
            row.put("items", cells);
            rows.add(row);
        }
        return rows;
    }

    /** 分配批阅任务：某题块由哪些老师阅（不分配时，有该班该学科权限的老师都可以阅）。 */
    @Transactional
    public void assignMarkers(CurrentIdentity ci, String type, Long id, String itemNo, List<Long> identityIds) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        assignMapper.delete(Wrappers.<ExMarkingAssign>lambdaQuery().eq(ExMarkingAssign::getSourceType, type)
                .eq(ExMarkingAssign::getSourceId, id).eq(ExMarkingAssign::getItemNo, itemNo));
        for (Long iid : identityIds) {
            ExMarkingAssign m = new ExMarkingAssign();
            m.setSourceType(type);
            m.setSourceId(id);
            m.setItemNo(itemNo);
            m.setIdentityId(iid);
            assignMapper.insert(m);
        }
    }

    // ---------------- 问题卷 ----------------

    public ExProblemPaper report(CurrentIdentity ci, Long answerId, String itemNo, String type, String remark) {
        ExAnswer a = BizException.notNull(answerMapper.selectById(answerId), "答卷");
        SourceService.Source s = sourceService.load(a.getSourceType(), a.getSourceId());
        checkMarker(ci, s, itemNo);
        ExProblemPaper p = new ExProblemPaper();
        p.setAnswerId(answerId);
        p.setItemNo(itemNo);
        p.setProblemType(type == null ? "OTHER" : type);
        p.setRemark(remark);
        p.setProblemStatus("OPEN");
        p.setReporterId(ci.userId());
        problemMapper.insert(p);
        return p;
    }

    public List<ExProblemPaper> problems(CurrentIdentity ci, String type, Long id) {
        SourceService.Source s = sourceService.load(type, id);
        sourceService.checkAccess(ci, s);
        List<Long> ids = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, type)
                .eq(ExAnswer::getSourceId, id)).stream().map(ExAnswer::getId).toList();
        return ids.isEmpty() ? List.of() : problemMapper.selectList(Wrappers.<ExProblemPaper>lambdaQuery()
                .in(ExProblemPaper::getAnswerId, ids).orderByDesc(ExProblemPaper::getCreatedAt));
    }

    /** 题组长 / 管理员处理问题卷后，回到阅卷队列。 */
    @Transactional
    public void handleProblem(CurrentIdentity ci, Long problemId) {
        ExProblemPaper p = BizException.notNull(problemMapper.selectById(problemId), "问题卷");
        ExAnswer a = answerMapper.selectById(p.getAnswerId());
        sourceService.checkAccess(ci, sourceService.load(a.getSourceType(), a.getSourceId()));
        p.setProblemStatus("HANDLED");
        p.setHandledBy(ci.userId());
        p.setHandledAt(LocalDateTime.now());
        problemMapper.updateById(p);
    }

    // ---------------- 作文 AI 批阅 ----------------

    public record AiMarkResult(int total, int done, int failed, List<String> messages) {
    }

    /**
     * 作文 / 主观题 AI 批阅（PRD 6.4、➕P0 打通 AI 平台）：调用 AI 平台的作文批改智能体，分数夹在 [0, 满分]，
     * 记为 AI 来源，教师可以修改；识别不到文字的卷跳过（不扣费）。
     */
    public AiMarkResult aiMark(CurrentIdentity ci, String type, Long id, String itemNo) {
        SourceService.Source s = sourceService.load(type, id);
        checkMarker(ci, s, itemNo);
        checkNotPaused(s);
        int total = 0;
        int done = 0;
        int failed = 0;
        List<String> msgs = new ArrayList<>();
        for (ExAnswer a : currentAnswers(s)) {
            ExAnswerItem it = item(a.getId(), itemNo);
            if (it == null || ScoringService.MANUAL_SOURCES.contains(it.getScoreSource())) {
                continue;
            }
            total++;
            String text = it.getStudentAnswer();
            if (text == null || text.replaceAll("\\s", "").length() < 20) {
                failed++;
                msgs.add("学生 " + a.getStudentId() + "：未识别到作文内容，请人工批阅");
                continue;
            }
            try {
                AiApi.AiResult r = aiApi.run(ci.userId(), "essay", text, Map.of("fullScore",
                        it.getFullScore().intValue(), "subject", s.subject() == null ? "CHINESE" : s.subject()));
                JsonNode report = om.readTree(r.content());
                BigDecimal score = BigDecimal.valueOf(report.path("score").asDouble())
                        .max(BigDecimal.ZERO).min(it.getFullScore()).setScale(1, RoundingMode.HALF_UP);
                it.setAiComment(report.toString());
                itemMapper.updateById(it);
                scoring.setScore(a, it, score, "AI", ci.userId(), "AI 批阅", sourceService.publishedFor(s, a.getClassId()));
                done++;
            } catch (BizException e) {
                failed++;
                msgs.add("学生 " + a.getStudentId() + "：" + e.getMessage());
                if (e.getCode() == ErrorCode.BALANCE_NOT_ENOUGH.code()) {
                    break;
                }
            } catch (Exception e) {
                failed++;
                log.warn("AI 批阅失败 answer={}: {}", a.getId(), e.toString());
                msgs.add("学生 " + a.getStudentId() + "：AI 批阅失败，请人工批阅");
            }
        }
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        return new AiMarkResult(total, done, failed, msgs);
    }

    private ExAnswerItem item(Long answerId, String itemNo) {
        return itemMapper.selectOne(Wrappers.<ExAnswerItem>lambdaQuery().eq(ExAnswerItem::getAnswerId, answerId)
                .eq(ExAnswerItem::getItemNo, itemNo));
    }

    private void checkMarker(CurrentIdentity ci, SourceService.Source s, String itemNo) {
        List<ExMarkingAssign> assigns = assignMapper.selectList(Wrappers.<ExMarkingAssign>lambdaQuery()
                .eq(ExMarkingAssign::getSourceType, s.type()).eq(ExMarkingAssign::getSourceId, s.id())
                .eq(ExMarkingAssign::getItemNo, itemNo));
        if (!assigns.isEmpty() && assigns.stream().noneMatch(m -> m.getIdentityId().equals(ci.identityId()))
                && !ci.hasPerm("exam:admin")) {
            throw new BizException(ErrorCode.FORBIDDEN, "这道题没有分配给你批阅");
        }
        if (assigns.isEmpty()) {
            sourceService.checkAccess(ci, s);
        }
    }

    private void checkNotPaused(SourceService.Source s) {
        if (s.raw() instanceof ExExam e && Set.of("PAUSED", "SCORE_AUDIT").contains(e.getExamStatus())) {
            throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "当前考试处于「" + FlowService.ExamStatus.valueOf(
                    e.getExamStatus()).label() + "」，暂不能阅卷");
        }
    }
}
