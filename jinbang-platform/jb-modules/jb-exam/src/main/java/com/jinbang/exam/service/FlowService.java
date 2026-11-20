package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.statemachine.StateMachine;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.domain.ExPublish;
import com.jinbang.exam.domain.ExScoreChange;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExExamMapper;
import com.jinbang.exam.mapper.ExHomeworkMapper;
import com.jinbang.exam.mapper.ExPublishMapper;
import com.jinbang.exam.mapper.ExScoreChangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业与考试状态机（PRD 11.5）、成绩审核 / 分批发布 / 撤回 / 发布后更正通知（D2、AC-05、AC-06）。
 */
@Service
@RequiredArgsConstructor
public class FlowService {
    // ---------- A 纸质作业 / B 线上作业 ----------
    public enum HwStatus implements StateMachine.Labeled {
        ASSIGNED("已布置"), COLLECTING("收集中"), PUBLISHED("已发布"), CLOSED("已截止"), MARKING("批阅中"),
        MARKED("批阅完成"), ARCHIVED("已归档");
        private final String label;

        HwStatus(String l) {
            label = l;
        }

        @Override
        public String label() {
            return label;
        }
    }

    public enum HwEvent { SCAN, COMPLETE_SCAN, CLOSE, FINISH_MARK, ARCHIVE }

    static final StateMachine<HwStatus, HwEvent> HW = StateMachine.<HwStatus, HwEvent>builder("作业", HwStatus.class)
            .on(HwStatus.ASSIGNED, HwEvent.SCAN, HwStatus.COLLECTING)
            .on(HwStatus.COLLECTING, HwEvent.COMPLETE_SCAN, HwStatus.MARKING)
            .on(HwStatus.PUBLISHED, HwEvent.CLOSE, HwStatus.MARKING)
            .on(HwStatus.MARKING, HwEvent.FINISH_MARK, HwStatus.MARKED)
            .on(HwStatus.MARKED, HwEvent.ARCHIVE, HwStatus.ARCHIVED)
            .build();

    // ---------- C 正式考试 ----------
    public enum ExamStatus implements StateMachine.Labeled {
        DRAFT("草稿"), WAIT_SCAN("待扫描"), SCANNING("扫描中"), MARKING("阅卷中"), PAUSED("暂停阅卷"),
        MARKED("阅卷完成（临时成绩）"), SCORE_AUDIT("成绩审核中"), AUDITED("成绩已审核"), PUBLISHED("已发布成绩"),
        LEARNING_PUBLISHED("已发布学情"), ARCHIVED("已归档");
        private final String label;

        ExamStatus(String l) {
            label = l;
        }

        @Override
        public String label() {
            return label;
        }
    }

    public enum ExamEvent { READY, SCAN, COMPLETE_SCAN, PAUSE, RESUME, FINISH_MARK, REOPEN, SUBMIT_AUDIT, APPROVE,
        REJECT, PUBLISH, WITHDRAW, PUBLISH_LEARNING, WITHDRAW_LEARNING, RESET_LEARNING, ARCHIVE }

    static final StateMachine<ExamStatus, ExamEvent> EXAM = StateMachine.<ExamStatus, ExamEvent>builder("考试", ExamStatus.class)
            .on(ExamStatus.DRAFT, ExamEvent.READY, ExamStatus.WAIT_SCAN)
            .on(ExamStatus.WAIT_SCAN, ExamEvent.SCAN, ExamStatus.SCANNING)
            .on(ExamStatus.SCANNING, ExamEvent.COMPLETE_SCAN, ExamStatus.MARKING)
            .on(ExamStatus.MARKING, ExamEvent.PAUSE, ExamStatus.PAUSED)
            .on(ExamStatus.PAUSED, ExamEvent.RESUME, ExamStatus.MARKING)
            .on(ExamStatus.MARKING, ExamEvent.FINISH_MARK, ExamStatus.MARKED)
            .onAny(ExamEvent.REOPEN, ExamStatus.MARKING, ExamStatus.MARKED, ExamStatus.AUDITED)
            .on(ExamStatus.MARKED, ExamEvent.SUBMIT_AUDIT, ExamStatus.SCORE_AUDIT)
            .on(ExamStatus.SCORE_AUDIT, ExamEvent.APPROVE, ExamStatus.AUDITED)
            .on(ExamStatus.SCORE_AUDIT, ExamEvent.REJECT, ExamStatus.MARKED)
            .on(ExamStatus.AUDITED, ExamEvent.PUBLISH, ExamStatus.PUBLISHED)
            .on(ExamStatus.MARKED, ExamEvent.PUBLISH, ExamStatus.PUBLISHED)
            .on(ExamStatus.PUBLISHED, ExamEvent.PUBLISH, ExamStatus.PUBLISHED)
            .on(ExamStatus.PUBLISHED, ExamEvent.WITHDRAW, ExamStatus.MARKED)
            .on(ExamStatus.PUBLISHED, ExamEvent.PUBLISH_LEARNING, ExamStatus.LEARNING_PUBLISHED)
            .on(ExamStatus.LEARNING_PUBLISHED, ExamEvent.WITHDRAW_LEARNING, ExamStatus.PUBLISHED)
            .on(ExamStatus.LEARNING_PUBLISHED, ExamEvent.RESET_LEARNING, ExamStatus.PUBLISHED)
            .on(ExamStatus.LEARNING_PUBLISHED, ExamEvent.ARCHIVE, ExamStatus.ARCHIVED)
            .build();

    private final ExHomeworkMapper homeworkMapper;
    private final ExExamMapper examMapper;
    private final ExPublishMapper publishMapper;
    private final ExAnswerMapper answerMapper;
    private final ExScoreChangeMapper changeMapper;
    private final SchoolApi schoolApi;
    private final NotifyApi notifyApi;
    private final ApplicationEventPublisher events;

    /** 第一次扫描：作业进入「收集中」，考试进入「扫描中」。 */
    public void onFirstScan(SourceService.Source s) {
        if (s.raw() instanceof ExHomework h && HwStatus.ASSIGNED.name().equals(h.getHwStatus())) {
            homework(h, HwEvent.SCAN);
        } else if (s.raw() instanceof ExExam e) {
            if (ExamStatus.DRAFT.name().equals(e.getExamStatus())) {
                throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "考试还在草稿状态，请先完成考生和答题卡设置");
            }
            if (ExamStatus.WAIT_SCAN.name().equals(e.getExamStatus())) {
                exam(e, ExamEvent.SCAN);
            }
        }
    }

    public void homework(ExHomework h, HwEvent ev) {
        HwStatus next = HW.fire(HwStatus.valueOf(h.getHwStatus()), ev);
        int n = homeworkMapper.update(Wrappers.<ExHomework>lambdaUpdate().set(ExHomework::getHwStatus, next.name())
                .set(next == HwStatus.MARKED, ExHomework::getMarkedAt, LocalDateTime.now())
                .eq(ExHomework::getId, h.getId()).eq(ExHomework::getHwStatus, h.getHwStatus()));
        BizException.throwIf(n == 0, ErrorCode.CONFLICT);
        h.setHwStatus(next.name());
    }

    public void exam(ExExam e, ExamEvent ev) {
        ExamStatus next = EXAM.fire(ExamStatus.valueOf(e.getExamStatus()), ev);
        int n = examMapper.update(Wrappers.<ExExam>lambdaUpdate().set(ExExam::getExamStatus, next.name())
                .eq(ExExam::getId, e.getId()).eq(ExExam::getExamStatus, e.getExamStatus()));
        BizException.throwIf(n == 0, ErrorCode.CONFLICT);
        e.setExamStatus(next.name());
    }

    /** 未阅完的答卷数（批阅完成前检查）。 */
    public long unmarked(String type, Long id) {
        return answerMapper.selectCount(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, type)
                .eq(ExAnswer::getSourceId, id).eq(ExAnswer::getIsCurrent, true).eq(ExAnswer::getAnswerStatus, "MARKING"));
    }

    // ---------------- 考试发布 ----------------

    /**
     * 发布成绩：可按班级分批发布（classIds 为空 = 全部班级）。需要审核的考试必须先审核（D2）。
     */
    @Transactional
    @OperLog(module = "考试-成绩", action = "发布成绩")
    public void publish(CurrentIdentity ci, ExExam e, Set<Long> allClasses, Set<Long> classIds) {
        if (Boolean.TRUE.equals(e.getScoreAuditRequired()) && ExamStatus.MARKED.name().equals(e.getExamStatus())) {
            throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "该考试需要先完成成绩审核再发布");
        }
        exam(e, ExamEvent.PUBLISH);
        Set<Long> target = classIds == null || classIds.isEmpty() ? allClasses : classIds;
        for (Long classId : target) {
            boolean exists = publishMapper.exists(Wrappers.<ExPublish>lambdaQuery().eq(ExPublish::getExamId, e.getId())
                    .eq(ExPublish::getClassId, classId).eq(ExPublish::getPublishKind, "SCORE").isNull(ExPublish::getWithdrawnAt));
            if (exists) {
                continue;
            }
            ExPublish p = new ExPublish();
            p.setExamId(e.getId());
            p.setClassId(classId);
            p.setPublishKind("SCORE");
            p.setPublishedAt(LocalDateTime.now());
            publishMapper.insert(p);
            notifyClass(e, classId, "成绩已发布", "「" + e.getExamName() + "」成绩已发布，请在学生端 / 家长端查看。");
        }
    }

    /** 撤回发布（AC-06）：学生端家长端立即看不到；已推送的通知不能撤回，补发「成绩暂时撤回」。 */
    @Transactional
    @OperLog(module = "考试-成绩", action = "撤回成绩发布")
    public void withdraw(CurrentIdentity ci, ExExam e) {
        List<ExPublish> active = publishMapper.selectList(Wrappers.<ExPublish>lambdaQuery().eq(ExPublish::getExamId, e.getId())
                .eq(ExPublish::getPublishKind, "SCORE").isNull(ExPublish::getWithdrawnAt));
        if (ExamStatus.LEARNING_PUBLISHED.name().equals(e.getExamStatus())) {
            exam(e, ExamEvent.WITHDRAW_LEARNING);
        }
        exam(e, ExamEvent.WITHDRAW);
        for (ExPublish p : active) {
            p.setWithdrawnAt(LocalDateTime.now());
            publishMapper.updateById(p);
            notifyClass(e, p.getClassId(), "成绩暂时撤回", "「" + e.getExamName() + "」成绩正在核对，已暂时撤回，核对后会重新发布。");
        }
    }

    /**
     * 发布后改分（AC-05）：重算报表；给受影响的学生和家长发「成绩更正」通知（原分 → 新分）；更正记录标记已通知。
     */
    public void notifyCorrections(SourceService.Source s, Long answerId) {
        List<ExScoreChange> pending = changeMapper.selectList(Wrappers.<ExScoreChange>lambdaQuery()
                .eq(ExScoreChange::getAnswerId, answerId).eq(ExScoreChange::getAfterPublish, true)
                .eq(ExScoreChange::getNotified, false));
        if (pending.isEmpty()) {
            return;
        }
        ExScoreChange first = pending.getFirst();
        ExScoreChange last = pending.getLast();
        Long studentId = first.getStudentId();
        List<Long> users = new ArrayList<>(schoolApi.parentUserIds(studentId));
        Long su = schoolApi.studentUserId(studentId);
        if (su != null) {
            users.add(su);
        }
        String content = String.format("「%s」成绩更正：总分 %s → %s。原因：%s", s.title(),
                fmt(first.getOldTotal()), fmt(last.getNewTotal()), last.getReason() == null ? "阅卷复核" : last.getReason());
        notifyApi.send(users, "SCORE", "成绩更正通知", content, s.type().toLowerCase() + ":" + s.id());
        pending.forEach(c -> {
            c.setNotified(true);
            changeMapper.updateById(c);
        });
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
    }

    private void notifyClass(ExExam e, Long classId, String title, String content) {
        List<Long> users = new ArrayList<>();
        for (SchoolApi.StudentDTO st : schoolApi.listStudentsByClass(classId)) {
            users.addAll(schoolApi.parentUserIds(st.id()));
            Long su = schoolApi.studentUserId(st.id());
            if (su != null) {
                users.add(su);
            }
        }
        notifyApi.send(users, "SCORE", title, content, "exam:" + e.getId());
    }

    private static String fmt(java.math.BigDecimal d) {
        return d == null ? "-" : d.stripTrailingZeros().toPlainString();
    }

    public static Map<String, String> examLabels() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (ExamStatus s : ExamStatus.values()) {
            m.put(s.name(), s.label());
        }
        return m;
    }
}
