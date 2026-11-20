package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.domain.ExSheetVersion;
import com.jinbang.exam.mapper.ExAnswerItemMapper;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExHomeworkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 布置作业（PRD 3.3 从试卷布置作业、D1、AC-01）、线上作答（11.5-B）、批阅完成。 */
@Service
@RequiredArgsConstructor
public class HomeworkService {
    private final ExHomeworkMapper homeworkMapper;
    private final ExAnswerMapper answerMapper;
    private final ExAnswerItemMapper itemMapper;
    private final SheetService sheetService;
    private final SourceService sourceService;
    private final FlowService flowService;
    private final ScoringService scoring;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;
    private final NotifyApi notifyApi;
    private final ApplicationEventPublisher events;

    /**
     * @param mode       OFFLINE 纸质（扫描）/ ONLINE 线上
     * @param studentIds 指定学生（为空 = 班级全体）
     */
    public record AssignReq(String title, String mode, String scanType, String subject, Long sheetId,
                            List<Long> classIds, List<Long> studentIds, LocalDateTime deadline, Boolean allowLate,
                            Boolean allowWithdraw, Boolean assignToTeacher) {
    }

    public record HomeworkCard(ExHomework homework, ScanService.BatchStats stats) {
    }

    /**
     * 学年一律取学校「当前学年」（D1，修复缺陷 19 学年写死）；班级必须是当前学年的、且当前身份对该班该学科有权限；
     * 指定学生必须属于所选班级。
     */
    @Transactional
    public ExHomework assign(CurrentIdentity ci, AssignReq req) {
        BizException.throwIf(req.classIds() == null || req.classIds().isEmpty(), ErrorCode.BAD_REQUEST, "请选择班级");
        boolean workbook = "WORKBOOK".equals(req.scanType());
        BizException.throwIf(req.sheetId() == null && !workbook, ErrorCode.BAD_REQUEST, "请选择答题卡（扫描教辅可不选）");
        SchoolApi.AcademicYearDTO year = schoolApi.currentYear(ci.tenantId());
        VisibleScope scope = dataScope.current();
        for (Long classId : req.classIds()) {
            SchoolApi.ClassDTO c = schoolApi.getClass(classId);
            BizException.throwIf(!Objects.equals(c.yearId(), year.id()), ErrorCode.BAD_REQUEST,
                    "「" + c.name() + "」不属于当前学年 " + year.name());
            if (!scope.canSee(classId, req.subject())) {
                throw new BizException(ErrorCode.FORBIDDEN, "你没有「" + c.name() + "」" + (req.subject() == null ? "" : req.subject()) + " 的任教权限");
            }
        }
        if (req.studentIds() != null && !req.studentIds().isEmpty()) {
            Set<Long> inClasses = new HashSet<>();
            req.classIds().forEach(c -> schoolApi.listStudentsByClass(c).forEach(s -> inClasses.add(s.id())));
            BizException.throwIf(!inClasses.containsAll(req.studentIds()), ErrorCode.BAD_REQUEST, "指定的学生不在所选班级中");
        }
        ExSheetVersion v = req.sheetId() == null ? null : sheetService.currentVersion(req.sheetId());
        BizException.throwIf("ONLINE".equals(req.mode()) && v == null, ErrorCode.BAD_REQUEST, "线上作业必须选择答题卡");
        boolean online = "ONLINE".equals(req.mode());
        ExHomework h = new ExHomework();
        h.setYearId(year.id());
        h.setTitle(req.title());
        h.setHwMode(online ? "ONLINE" : "OFFLINE");
        h.setScanType(req.scanType() == null ? "ANSWER_SHEET" : req.scanType());
        h.setSubject(req.subject());
        h.setSheetVersionId(v == null ? null : v.getId());
        h.setClassIds(req.classIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        h.setStudentIds(req.studentIds() == null ? null : req.studentIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
        h.setDeadline(req.deadline());
        h.setAllowLate(req.allowLate() == null || req.allowLate());
        h.setAllowWithdraw(req.allowWithdraw() == null || req.allowWithdraw());
        h.setAssignToTeacher(Boolean.TRUE.equals(req.assignToTeacher()));
        h.setAnswerPublished(false);
        h.setHwStatus(online ? FlowService.HwStatus.PUBLISHED.name() : FlowService.HwStatus.ASSIGNED.name());
        h.setCreatorIdentityId(ci.identityId());
        homeworkMapper.insert(h);
        if (online) {
            v.setFrozen(true);
            sheetService.lock(v);
        }
        // 学生端触达
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, h.getId());
        List<Long> users = new ArrayList<>();
        sourceService.targets(s).keySet().forEach(sid -> {
            Long u = schoolApi.studentUserId(sid);
            if (u != null) {
                users.add(u);
            }
        });
        notifyApi.send(users, "HOMEWORK", "新作业：" + h.getTitle(),
                h.getDeadline() == null ? "请按时完成" : "截止时间 " + h.getDeadline(), "homework:" + h.getId());
        return h;
    }

    /** 作业列表（卡片）：只列当前身份能看到的班级的作业。 */
    public List<HomeworkCard> list(CurrentIdentity ci, Long classId, ScanService scanService) {
        VisibleScope scope = dataScope.current();
        List<ExHomework> all = homeworkMapper.selectList(Wrappers.<ExHomework>lambdaQuery()
                .orderByDesc(ExHomework::getCreatedAt));
        List<HomeworkCard> out = new ArrayList<>();
        for (ExHomework h : all) {
            Set<Long> classes = SourceService.ids(h.getClassIds());
            if (classId != null && !classes.contains(classId)) {
                continue;
            }
            boolean visible = Objects.equals(h.getCreatorIdentityId(), ci.identityId()) || scope.isSchoolWide()
                    || classes.stream().anyMatch(c -> scope.canSee(c, h.getSubject()));
            if (visible) {
                out.add(new HomeworkCard(h, scanService.statsOf(sourceService.load(SourceService.HOMEWORK, h.getId()))));
            }
        }
        return out;
    }

    @Transactional
    public void completeScan(CurrentIdentity ci, Long id) {
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, id);
        sourceService.checkAccess(ci, s);
        flowService.homework((ExHomework) s.raw(), FlowService.HwEvent.COMPLETE_SCAN);
    }

    /** 批阅完成：所有当前答卷都已阅完才行；之后按钮变为「作业诊断」。 */
    @Transactional
    public void finishMarking(CurrentIdentity ci, Long id) {
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, id);
        sourceService.checkAccess(ci, s);
        long left = flowService.unmarked(SourceService.HOMEWORK, id);
        BizException.throwIf(left > 0, ErrorCode.STATE_TRANSITION_DENIED, "还有 " + left + " 份答卷没有阅完");
        ExHomework h = (ExHomework) s.raw();
        if (FlowService.HwStatus.COLLECTING.name().equals(h.getHwStatus())) {
            flowService.homework(h, FlowService.HwEvent.COMPLETE_SCAN);
        }
        flowService.homework(h, FlowService.HwEvent.FINISH_MARK);
        events.publishEvent(new ScoresChangedEvent(SourceService.HOMEWORK, id, s.tenantId()));
    }

    // ---------------- 线上作业（学生端） ----------------

    public record OnlineSubmit(Map<String, String> answers) {
    }

    /** 学生线上提交：截止后不能提交（除非允许迟交，标记迟交）；截止前可撤回重交（教师可配置）。 */
    @Transactional
    public ExAnswer submitOnline(CurrentIdentity ci, Long homeworkId, Long studentId, OnlineSubmit req) {
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, homeworkId);
        ExHomework h = (ExHomework) s.raw();
        BizException.throwIf(!"ONLINE".equals(h.getHwMode()), ErrorCode.BAD_REQUEST, "这是纸质作业，请交给老师扫描");
        Map<Long, Long> targets = sourceService.targets(s);
        BizException.throwIf(!targets.containsKey(studentId), ErrorCode.FORBIDDEN);
        boolean late = h.getDeadline() != null && LocalDateTime.now().isAfter(h.getDeadline());
        BizException.throwIf(late && !Boolean.TRUE.equals(h.getAllowLate()), ErrorCode.STATE_TRANSITION_DENIED, "作业已截止");
        BizException.throwIf(!Set.of("PUBLISHED", "MARKING").contains(h.getHwStatus()) && !late,
                ErrorCode.STATE_TRANSITION_DENIED, "当前不能提交");
        ExAnswer existing = answerMapper.selectOne(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, s.type())
                .eq(ExAnswer::getSourceId, s.id()).eq(ExAnswer::getStudentId, studentId).eq(ExAnswer::getIsCurrent, true));
        if (existing != null) {
            BizException.throwIf(!Boolean.TRUE.equals(h.getAllowWithdraw()) || late, ErrorCode.STATE_TRANSITION_DENIED,
                    "已提交，不能重复提交");
            existing.setIsCurrent(false);
            existing.setAnswerStatus("REPLACED");
            answerMapper.updateById(existing);
        }
        ExSheetVersion v = sheetService.version(h.getSheetVersionId());
        ExAnswer a = new ExAnswer();
        a.setSourceType(s.type());
        a.setSourceId(s.id());
        a.setStudentId(studentId);
        a.setClassId(targets.get(studentId));
        a.setSheetVersionId(v.getId());
        a.setAnswerStatus("MARKING");
        a.setIsCurrent(true);
        a.setLateFlag(late);
        a.setMakeupFlag(FlowService.HwStatus.MARKED.name().equals(h.getHwStatus()));
        a.setSubmittedAt(LocalDateTime.now());
        answerMapper.insert(a);
        for (SheetService.Block b : sheetService.blocks(v)) {
            ExAnswerItem it = new ExAnswerItem();
            it.setAnswerId(a.getId());
            it.setItemNo(b.no());
            it.setItemType(b.type());
            it.setObjective(b.objective());
            it.setFullScore(b.fullScore());
            it.setStdAnswer(b.answer());
            it.setStudentAnswer(req.answers() == null ? null : req.answers().get(b.no()));
            it.setNeedsReview(false);
            if (b.objective()) {
                it.setScore(ScoringService.autoScore(it));
                it.setScoreSource("AUTO");
            }
            itemMapper.insert(it);
        }
        scoring.recalc(a);
        events.publishEvent(new ScoresChangedEvent(s.type(), s.id(), s.tenantId()));
        return a;
    }

    /** 线上作业截止 → 进入批阅。 */
    @Transactional
    public void close(CurrentIdentity ci, Long id) {
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, id);
        sourceService.checkAccess(ci, s);
        flowService.homework((ExHomework) s.raw(), FlowService.HwEvent.CLOSE);
    }

    @Transactional
    public void publishAnswer(CurrentIdentity ci, Long id) {
        SourceService.Source s = sourceService.load(SourceService.HOMEWORK, id);
        sourceService.checkAccess(ci, s);
        ExHomework h = (ExHomework) s.raw();
        h.setAnswerPublished(true);
        homeworkMapper.updateById(h);
    }
}
