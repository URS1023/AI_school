package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.api.system.YearUpgradeCheck;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExExaminee;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.mapper.ExExamMapper;
import com.jinbang.exam.mapper.ExExamineeMapper;
import com.jinbang.exam.mapper.ExHomeworkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 考试中心（PRD 6.1、11.5-C）：建考、考生、答题卡、阅卷流程、成绩审核与发布、学情发布。 */
@Service
@RequiredArgsConstructor
public class ExamService implements YearUpgradeCheck {
    private final ExExamMapper examMapper;
    private final ExExamineeMapper examineeMapper;
    private final ExHomeworkMapper homeworkMapper;
    private final SheetService sheetService;
    private final SourceService sourceService;
    private final FlowService flowService;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;
    private final ApplicationEventPublisher events;

    public record CreateReq(String name, String examType, Long gradeId, String subject, Long sheetId,
                            LocalDateTime examTime, Boolean scoreAuditRequired, List<Long> classIds) {
    }

    /** 任课教师只能建所教班级的单科测验；年级主任本年级；管理员全校（权限矩阵 8.4）。 */
    @Transactional
    @OperLog(module = "考试", action = "创建考试")
    public ExExam create(CurrentIdentity ci, CreateReq req) {
        BizException.throwIf(req.subject() == null || req.name() == null, ErrorCode.BAD_REQUEST, "请填写考试名称和学科");
        SchoolApi.AcademicYearDTO year = schoolApi.currentYear(ci.tenantId());
        VisibleScope scope = dataScope.current();
        List<Long> classIds = req.classIds() == null ? List.of() : req.classIds();
        for (Long c : classIds) {
            SchoolApi.ClassDTO dto = schoolApi.getClass(c);
            BizException.throwIf(!Objects.equals(dto.yearId(), year.id()), ErrorCode.BAD_REQUEST, "「" + dto.name() + "」不属于当前学年");
            if (!scope.canSee(c, req.subject())) {
                throw new BizException(ErrorCode.FORBIDDEN, "你没有「" + dto.name() + "」的建考权限");
            }
        }
        ExExam e = new ExExam();
        e.setYearId(year.id());
        e.setExamName(req.name());
        e.setExamType(req.examType() == null ? "SCHOOL" : req.examType());
        e.setGradeId(req.gradeId());
        e.setSubject(req.subject());
        e.setExamTime(req.examTime());
        e.setExamStatus(FlowService.ExamStatus.DRAFT.name());
        e.setScoreAuditRequired(req.scoreAuditRequired() == null || req.scoreAuditRequired());
        e.setShowRank(false);
        e.setCreatorIdentityId(ci.identityId());
        if (req.sheetId() != null) {
            e.setSheetVersionId(sheetService.currentVersion(req.sheetId()).getId());
        }
        examMapper.insert(e);
        importFromClasses(e, classIds);
        return e;
    }

    /** 考生管理：从班级导入（已有的不重复导入）。 */
    public int importFromClasses(ExExam e, List<Long> classIds) {
        int n = 0;
        for (Long c : classIds) {
            for (SchoolApi.StudentDTO st : schoolApi.listStudentsByClass(c)) {
                ExExaminee x = new ExExaminee();
                x.setExamId(e.getId());
                x.setStudentId(st.id());
                x.setClassId(c);
                x.setExamineeStatus("NORMAL");
                try {
                    examineeMapper.insert(x);
                    n++;
                } catch (DuplicateKeyException ignored) {
                    // 已是考生
                }
            }
        }
        return n;
    }

    public ExExam get(CurrentIdentity ci, Long id) {
        SourceService.Source s = sourceService.load(SourceService.EXAM, id);
        sourceService.checkAccess(ci, s);
        return (ExExam) s.raw();
    }

    public List<ExExam> list(CurrentIdentity ci, String status) {
        VisibleScope scope = dataScope.current();
        List<ExExam> out = new ArrayList<>();
        for (ExExam e : examMapper.selectList(Wrappers.<ExExam>lambdaQuery().eq(status != null, ExExam::getExamStatus, status)
                .orderByDesc(ExExam::getCreatedAt))) {
            SourceService.Source s = sourceService.load(SourceService.EXAM, e.getId());
            if (Objects.equals(e.getCreatorIdentityId(), ci.identityId()) || scope.isSchoolWide()
                    || s.classIds().stream().anyMatch(c -> scope.canSee(c, e.getSubject()))) {
                out.add(e);
            }
        }
        return out;
    }

    public List<ExExaminee> examinees(CurrentIdentity ci, Long examId) {
        get(ci, examId);
        return examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getExamId, examId));
    }

    /** 缺考 / 免考 / 作废：不计入均分和排名，单独统计人数。 */
    @Transactional
    @OperLog(module = "考试-考生", action = "设置考生状态")
    public void setExamineeStatus(CurrentIdentity ci, Long examId, Long studentId, String status) {
        get(ci, examId);
        BizException.throwIf(!Set.of("NORMAL", "ABSENT", "EXEMPT", "VOID").contains(status), ErrorCode.BAD_REQUEST);
        int n = examineeMapper.update(Wrappers.<ExExaminee>lambdaUpdate().set(ExExaminee::getExamineeStatus, status)
                .eq(ExExaminee::getExamId, examId).eq(ExExaminee::getStudentId, studentId));
        BizException.throwIf(n == 0, ErrorCode.NOT_FOUND, "该学生不是本场考生");
        events.publishEvent(new ScoresChangedEvent(SourceService.EXAM, examId, ci.tenantId()));
    }

    @Transactional
    public ExExam bindSheet(CurrentIdentity ci, Long examId, Long sheetId) {
        ExExam e = get(ci, examId);
        BizException.throwIf(!FlowService.ExamStatus.DRAFT.name().equals(e.getExamStatus()), ErrorCode.STATE_TRANSITION_DENIED,
                "考试已开始扫描，不能更换答题卡");
        e.setSheetVersionId(sheetService.currentVersion(sheetId).getId());
        examMapper.updateById(e);
        return e;
    }

    /**
     * 流程推进。action：READY 完成准备 / COMPLETE_SCAN 完成扫描 / PAUSE / RESUME / FINISH_MARK 阅卷完成 /
     * REOPEN 重新阅卷 / SUBMIT_AUDIT 提交审核 / APPROVE / REJECT / PUBLISH_LEARNING / RESET_LEARNING / ARCHIVE。
     */
    @Transactional
    @OperLog(module = "考试-流程", action = "推进考试流程")
    public ExExam advance(CurrentIdentity ci, Long examId, String action) {
        ExExam e = get(ci, examId);
        FlowService.ExamEvent ev = FlowService.ExamEvent.valueOf(action);
        switch (ev) {
            case READY -> {
                BizException.throwIf(e.getSheetVersionId() == null, ErrorCode.STATE_TRANSITION_DENIED, "请先设置考试答题卡");
                BizException.throwIf(!examineeMapper.exists(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getExamId, examId)),
                        ErrorCode.STATE_TRANSITION_DENIED, "请先导入考生");
            }
            case FINISH_MARK -> {
                long left = flowService.unmarked(SourceService.EXAM, examId);
                BizException.throwIf(left > 0, ErrorCode.STATE_TRANSITION_DENIED, "还有 " + left + " 份答卷没有阅完");
            }
            case APPROVE, REJECT -> {
                // 成绩审核由年级主任或教务（有发布权限）确认，不能由建考人自己审（D2）
                BizException.throwIf(!ci.hasPerm("exam:publish"), ErrorCode.FORBIDDEN);
            }
            case PUBLISH, WITHDRAW -> throw new BizException(ErrorCode.BAD_REQUEST, "请使用发布 / 撤回接口");
            default -> {
            }
        }
        flowService.exam(e, ev);
        if (ev == FlowService.ExamEvent.RESET_LEARNING || ev == FlowService.ExamEvent.REOPEN) {
            events.publishEvent(new ScoresChangedEvent(SourceService.EXAM, examId, ci.tenantId()));
        }
        return e;
    }

    @Transactional
    public ExExam publish(CurrentIdentity ci, Long examId, List<Long> classIds) {
        BizException.throwIf(!ci.hasPerm("exam:publish"), ErrorCode.FORBIDDEN);
        ExExam e = get(ci, examId);
        SourceService.Source s = sourceService.load(SourceService.EXAM, examId);
        flowService.publish(ci, e, s.classIds(), classIds == null ? Set.of() : Set.copyOf(classIds));
        events.publishEvent(new ScoresChangedEvent(SourceService.EXAM, examId, ci.tenantId()));
        return e;
    }

    @Transactional
    public ExExam withdraw(CurrentIdentity ci, Long examId) {
        BizException.throwIf(!ci.hasPerm("exam:publish"), ErrorCode.FORBIDDEN);
        ExExam e = get(ci, examId);
        flowService.withdraw(ci, e);
        return e;
    }

    /** 学年升级前检查：未完成阅卷、未发布成绩的考试和作业（PRD 11.8）。 */
    @Override
    public List<String> check(Long tenantId, Long yearId) {
        return TenantContext.withTenant(tenantId, () -> {
            List<String> w = new ArrayList<>();
            examMapper.selectList(Wrappers.<ExExam>lambdaQuery().eq(ExExam::getYearId, yearId)).forEach(e -> {
                FlowService.ExamStatus st = FlowService.ExamStatus.valueOf(e.getExamStatus());
                if (st.ordinal() < FlowService.ExamStatus.PUBLISHED.ordinal()) {
                    w.add("考试「" + e.getExamName() + "」状态为「" + st.label() + "」，尚未发布成绩");
                }
            });
            homeworkMapper.selectList(Wrappers.<ExHomework>lambdaQuery().eq(ExHomework::getYearId, yearId)
                    .in(ExHomework::getHwStatus, "COLLECTING", "MARKING")).forEach(h ->
                    w.add("作业「" + h.getTitle() + "」尚未批阅完成"));
            return w;
        });
    }
}
