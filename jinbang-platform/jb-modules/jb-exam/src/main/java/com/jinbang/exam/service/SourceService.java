package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExExaminee;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.domain.ExPublish;
import com.jinbang.exam.mapper.ExExamMapper;
import com.jinbang.exam.mapper.ExExamineeMapper;
import com.jinbang.exam.mapper.ExHomeworkMapper;
import com.jinbang.exam.mapper.ExPublishMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 作业和考试的统一视图：目标学生、所属班级、关联答题卡、截止时间、权限、发布状态。 */
@Service
@RequiredArgsConstructor
public class SourceService {
    public static final String HOMEWORK = "HOMEWORK";
    public static final String EXAM = "EXAM";

    private final ExHomeworkMapper homeworkMapper;
    private final ExExamMapper examMapper;
    private final ExExamineeMapper examineeMapper;
    private final ExPublishMapper publishMapper;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;

    public record Source(String type, Long id, Long tenantId, String title, String subject, Long sheetVersionId,
                         Set<Long> classIds, LocalDateTime deadline, boolean allowLate, String status,
                         Long creatorIdentityId, LocalDateTime time, Object raw) {
        public boolean isExam() {
            return EXAM.equals(type);
        }
    }

    public Source load(String type, Long id) {
        if (HOMEWORK.equals(type)) {
            ExHomework h = BizException.notNull(homeworkMapper.selectById(id), "作业");
            return new Source(type, id, h.getTenantId(), h.getTitle(), h.getSubject(), h.getSheetVersionId(),
                    ids(h.getClassIds()), h.getDeadline(), Boolean.TRUE.equals(h.getAllowLate()), h.getHwStatus(),
                    h.getCreatorIdentityId(), h.getCreatedAt(), h);
        }
        if (EXAM.equals(type)) {
            ExExam e = BizException.notNull(examMapper.selectById(id), "考试");
            Set<Long> classes = examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getExamId, id))
                    .stream().map(ExExaminee::getClassId).collect(Collectors.toCollection(LinkedHashSet::new));
            return new Source(type, id, e.getTenantId(), e.getExamName(), e.getSubject(), e.getSheetVersionId(), classes,
                    null, true, e.getExamStatus(), e.getCreatorIdentityId(), e.getExamTime() == null ? e.getCreatedAt()
                    : e.getExamTime(), e);
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "未知类型：" + type);
    }

    public static Set<Long> ids(String csv) {
        if (csv == null || csv.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 目标学生 → 所在班级。作业：指定学生或班级全体；考试：考生名单。 */
    public Map<Long, Long> targets(Source s) {
        Map<Long, Long> out = new HashMap<>();
        if (s.isExam()) {
            examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getExamId, s.id()))
                    .forEach(e -> out.put(e.getStudentId(), e.getClassId()));
            return out;
        }
        ExHomework h = (ExHomework) s.raw();
        Set<Long> specified = ids(h.getStudentIds());
        for (Long classId : s.classIds()) {
            for (SchoolApi.StudentDTO st : schoolApi.listStudentsByClass(classId)) {
                if (specified.isEmpty() || specified.contains(st.id())) {
                    out.put(st.id(), classId);
                }
            }
        }
        return out;
    }

    public Map<Long, String> examineeStatus(Source s) {
        if (!s.isExam()) {
            return Map.of();
        }
        return examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery().eq(ExExaminee::getExamId, s.id()))
                .stream().collect(Collectors.toMap(ExExaminee::getStudentId, ExExaminee::getExamineeStatus));
    }

    /**
     * 教师能否操作该作业 / 考试（R2、R8）：创建人；或者对其中某个班有该学科权限；学校管理员 / 校领导全校。
     */
    public void checkAccess(CurrentIdentity ci, Source s) {
        if (!Objects.equals(ci.tenantId(), s.tenantId()) && !ci.isPlatform()) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据不存在");
        }
        if (Objects.equals(ci.identityId(), s.creatorIdentityId())) {
            return;
        }
        VisibleScope scope = dataScope.current();
        if (scope.isSchoolWide()) {
            return;
        }
        for (Long c : s.classIds()) {
            if (scope.canSee(c, s.subject())) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN);
    }

    /** 学生 / 家长端：该班成绩是否已发布且未撤回。作业批阅完成即对学生可见。 */
    public boolean publishedFor(Source s, Long classId) {
        if (!s.isExam()) {
            return Set.of("MARKED", "ARCHIVED").contains(s.status());
        }
        return publishMapper.exists(Wrappers.<ExPublish>lambdaQuery().eq(ExPublish::getExamId, s.id())
                .eq(ExPublish::getClassId, classId).eq(ExPublish::getPublishKind, "SCORE").isNull(ExPublish::getWithdrawnAt));
    }

    public boolean anyPublished(Source s) {
        if (!s.isExam()) {
            return false;
        }
        return publishMapper.exists(Wrappers.<ExPublish>lambdaQuery().eq(ExPublish::getExamId, s.id())
                .eq(ExPublish::getPublishKind, "SCORE").isNull(ExPublish::getWithdrawnAt));
    }

    public List<ExHomework> homeworksOfClasses(Set<Long> classIds) {
        return homeworkMapper.selectList(Wrappers.<ExHomework>lambdaQuery().orderByDesc(ExHomework::getCreatedAt))
                .stream().filter(h -> ids(h.getClassIds()).stream().anyMatch(classIds::contains)).toList();
    }
}
