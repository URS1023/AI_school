package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.util.Masks;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.domain.EduTeacher;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduStudentMapper;
import com.jinbang.system.mapper.EduTeacherMapper;
import com.jinbang.system.mapper.SysTenantMapper;
import com.jinbang.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 前端用的「通讯录」类查询（前端方案 7.2、7.5、7.6）：按 ID 批量取姓名、本校教师精简列表、学生 / 家长的「我是谁」。
 * 都受数据权限约束：看不到的人不返回（不报错，前端显示「—」）。
 */
@Service
@RequiredArgsConstructor
public class DirectoryService {
    private static final int MAX_IDS = 500;

    private final EduStudentMapper studentMapper;
    private final EduTeacherMapper teacherMapper;
    private final EduClassMapper classMapper;
    private final SysUserMapper userMapper;
    private final SysTenantMapper tenantMapper;
    private final DataScopeProvider dataScope;

    public record StudentName(Long id, String name, String studentNo, Long classId, String className) {
    }

    public record Names(Map<Long, StudentName> students, Map<Long, String> users) {
    }

    public Names names(CurrentIdentity ci, Collection<Long> studentIds, Collection<Long> userIds) {
        BizException.throwIf((studentIds != null && studentIds.size() > MAX_IDS) || (userIds != null && userIds.size() > MAX_IDS),
                ErrorCode.BAD_REQUEST, "一次最多查询 " + MAX_IDS + " 个");
        Map<Long, StudentName> students = new LinkedHashMap<>();
        if (studentIds != null && !studentIds.isEmpty()) {
            VisibleScope scope = dataScope.current();
            List<EduStudent> list = studentMapper.selectByIds(studentIds);
            Map<Long, String> classNames = classNames(list.stream().map(EduStudent::getClassId).toList());
            for (EduStudent s : list) {
                boolean visible = scope.isSchoolWide() || scope.canSeeStudent(s.getId())
                        || scope.anyClassIds().contains(s.getClassId()) || !scope.schoolSubjects().isEmpty();
                if (visible) {
                    students.put(s.getId(), new StudentName(s.getId(), s.getStudentName(), s.getStudentNo(), s.getClassId(),
                            classNames.get(s.getClassId())));
                }
            }
        }
        Map<Long, String> users = new LinkedHashMap<>();
        if (userIds != null && !userIds.isEmpty()) {
            boolean member = ci.type().isSchoolMember() || ci.isPlatform();
            Map<Long, String> teacherNames = member && !userIds.isEmpty()
                    ? teacherMapper.selectList(Wrappers.<EduTeacher>lambdaQuery().in(EduTeacher::getUserId, userIds))
                    .stream().collect(Collectors.toMap(EduTeacher::getUserId, EduTeacher::getTeacherName, (a, b) -> a))
                    : Map.of();
            for (SysUser u : userMapper.selectByIds(userIds)) {
                if (u.getId().equals(ci.userId())) {
                    users.put(u.getId(), u.getNickname());
                } else if (teacherNames.containsKey(u.getId())) {
                    users.put(u.getId(), teacherNames.get(u.getId()));
                } else if (ci.isPlatform()) {
                    users.put(u.getId(), u.getNickname());
                }
            }
        }
        return new Names(students, users);
    }

    public record TeacherBrief(Long identityId, Long userId, String name, String subjects, String phone) {
    }

    /** 本校在职教师精简列表（选集体备课成员、分配阅卷）：只有本校教职工可调用，手机号脱敏。 */
    public List<TeacherBrief> teachers(CurrentIdentity ci, String keyword) {
        BizException.throwIf(!ci.type().isSchoolMember(), ErrorCode.FORBIDDEN);
        return teacherMapper.selectList(Wrappers.<EduTeacher>lambdaQuery().eq(EduTeacher::getTeacherStatus, "ACTIVE")
                        .like(keyword != null && !keyword.isBlank(), EduTeacher::getTeacherName, keyword)
                        .orderByAsc(EduTeacher::getTeacherName))
                .stream().map(t -> new TeacherBrief(t.getIdentityId(), t.getUserId(), t.getTeacherName(), t.getSubjects(),
                        Masks.phone(t.getPhone()))).toList();
    }

    public record PortalStudent(Long studentId, String name, String studentNo, Long classId, String className) {
    }

    public record PortalMe(String role, Long schoolId, String schoolName, List<PortalStudent> students) {
    }

    /** 学生端 / 家长端「我是谁」：学生返回自己，家长返回已绑定的孩子（当前身份所在学校）。 */
    public PortalMe portalMe(CurrentIdentity ci) {
        BizException.throwIf(ci.type() != IdentityType.STUDENT && ci.type() != IdentityType.PARENT, ErrorCode.FORBIDDEN);
        VisibleScope scope = dataScope.current();
        List<EduStudent> list = scope.studentIds().isEmpty() ? List.of() : studentMapper.selectByIds(scope.studentIds());
        Map<Long, String> classNames = classNames(list.stream().map(EduStudent::getClassId).toList());
        SysTenant t = tenantMapper.selectById(ci.tenantId());
        return new PortalMe(ci.type().name(), ci.tenantId(), t == null ? null : t.getTenantName(), list.stream()
                .map(s -> new PortalStudent(s.getId(), s.getStudentName(), s.getStudentNo(), s.getClassId(),
                        classNames.get(s.getClassId()))).toList());
    }

    private Map<Long, String> classNames(List<Long> classIds) {
        List<Long> ids = classIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : classMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(EduClass::getId, EduClass::getClassName, (a, b) -> a));
    }

    @SuppressWarnings("unused")
    private static <T> Map<Long, T> index(List<T> list, Function<T, Long> key) {
        return list.stream().collect(Collectors.toMap(key, Function.identity()));
    }
}
