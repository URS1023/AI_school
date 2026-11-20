package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExExaminee;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExExamineeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 学生端 / 家长端（PRD 9.7、9.8、R7）：学生只能看自己，家长只能看已绑定的孩子；
 * 考试成绩只有本班已发布且未撤回才可见（AC-06）；排名默认不展示（D2）。
 */
@Service
@RequiredArgsConstructor
public class PortalService {
    private final SourceService sourceService;
    private final ScoringService scoring;
    private final ExAnswerMapper answerMapper;
    private final ExExamineeMapper examineeMapper;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;

    public record ResultView(String sourceType, Long sourceId, String title, String subject, LocalDateTime time,
                             String state, BigDecimal score, BigDecimal fullScore, Boolean late, List<ItemView> items) {
    }

    public record ItemView(String no, BigDecimal score, BigDecimal fullScore, String answer, String stdAnswer) {
    }

    public record HomeworkTodo(Long homeworkId, String title, String subject, String mode, LocalDateTime deadline,
                               boolean submitted) {
    }

    /** 当前身份可以查看的学生；studentId 为空时取唯一的那个（学生本人 / 只绑了一个孩子的家长）。 */
    public Long resolveStudent(CurrentIdentity ci, Long studentId) {
        VisibleScope scope = dataScope.current();
        if (studentId == null) {
            BizException.throwIf(scope.studentIds().size() != 1, ErrorCode.BAD_REQUEST, "请选择孩子");
            return scope.studentIds().iterator().next();
        }
        if (!scope.canSeeStudent(studentId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return studentId;
    }

    public List<ResultView> results(CurrentIdentity ci, Long studentId) {
        Long sid = resolveStudent(ci, studentId);
        SchoolApi.StudentDTO st = schoolApi.getStudent(sid);
        List<ResultView> out = new ArrayList<>();
        for (ExHomework h : sourceService.homeworksOfClasses(Set.of(st.classId()))) {
            out.add(view(sourceService.load(SourceService.HOMEWORK, h.getId()), sid, st.classId()));
        }
        for (ExExaminee x : examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getStudentId, sid))) {
            out.add(view(sourceService.load(SourceService.EXAM, x.getExamId()), sid, x.getClassId()));
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }

    private ResultView view(SourceService.Source s, Long sid, Long classId) {
        List<ExAnswer> mine = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, s.type())
                .eq(ExAnswer::getSourceId, s.id()).eq(ExAnswer::getStudentId, sid).eq(ExAnswer::getIsCurrent, true)
                .ne(ExAnswer::getAnswerStatus, "EXCEPTION"));
        ExAnswer a = mine.isEmpty() ? null : mine.getFirst();
        boolean visible = sourceService.publishedFor(s, classId);
        if (a == null) {
            return new ResultView(s.type(), s.id(), s.title(), s.subject(), s.time(), "未提交", null, null, null, List.of());
        }
        if (!visible) {
            return new ResultView(s.type(), s.id(), s.title(), s.subject(), s.time(), s.isExam() ? "成绩未发布" : "批阅中",
                    null, null, a.getLateFlag(), List.of());
        }
        boolean showStd = !(s.raw() instanceof ExHomework h) || Boolean.TRUE.equals(h.getAnswerPublished())
                || "MARKED".equals(h.getHwStatus());
        List<ItemView> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal full = BigDecimal.ZERO;
        for (ExAnswer x : mine) {
            scoring.items(x.getId()).forEach(it -> items.add(new ItemView(it.getItemNo(), it.getScore(), it.getFullScore(),
                    it.getStudentAnswer(), showStd ? it.getStdAnswer() : null)));
            total = total.add(x.getTotalScore() == null ? BigDecimal.ZERO : x.getTotalScore());
            full = full.add(x.getFullScore() == null ? BigDecimal.ZERO : x.getFullScore());
        }
        String state = s.raw() instanceof ExExam ? "已发布" : "已批阅";
        return new ResultView(s.type(), s.id(), s.title(), s.subject(), s.time(), state, total, full, a.getLateFlag(), items);
    }

    public List<HomeworkTodo> homeworks(CurrentIdentity ci, Long studentId) {
        Long sid = resolveStudent(ci, studentId);
        SchoolApi.StudentDTO st = schoolApi.getStudent(sid);
        List<HomeworkTodo> out = new ArrayList<>();
        for (ExHomework h : sourceService.homeworksOfClasses(Set.of(st.classId()))) {
            if (!sourceService.targets(sourceService.load(SourceService.HOMEWORK, h.getId())).containsKey(sid)) {
                continue;
            }
            boolean submitted = answerMapper.exists(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, SourceService.HOMEWORK)
                    .eq(ExAnswer::getSourceId, h.getId()).eq(ExAnswer::getStudentId, sid).eq(ExAnswer::getIsCurrent, true));
            out.add(new HomeworkTodo(h.getId(), h.getTitle(), h.getSubject(), h.getHwMode(), h.getDeadline(), submitted));
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static boolean marked(ExAnswerItem it) {
        return it.getScore() != null;
    }
}
