package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduAcademicYear;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.EduParentBinding;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.mapper.EduAcademicYearMapper;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduGradeMapper;
import com.jinbang.system.mapper.EduParentBindingMapper;
import com.jinbang.system.mapper.EduStudentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 对其他模块提供学校基础数据。调用方处于什么租户上下文，就只能查到该租户的数据。 */
@Service
@RequiredArgsConstructor
public class SchoolApiImpl implements SchoolApi {
    private final EduAcademicYearMapper yearMapper;
    private final EduClassMapper classMapper;
    private final EduGradeMapper gradeMapper;
    private final EduStudentMapper studentMapper;
    private final EduParentBindingMapper bindingMapper;

    @Override
    public AcademicYearDTO currentYear(Long tenantId) {
        EduAcademicYear y = TenantContext.withTenant(tenantId, () -> yearMapper.selectList(
                Wrappers.<EduAcademicYear>lambdaQuery().eq(EduAcademicYear::getIsCurrent, true)).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "学校尚未设置当前学年，请联系学校管理员")));
        return new AcademicYearDTO(y.getId(), y.getTenantId(), y.getYearName(), y.getStartYear());
    }

    @Override
    public ClassDTO getClass(Long classId) {
        EduClass c = BizException.notNull(classMapper.selectById(classId), "班级");
        return toDto(c, gradeMapper.selectById(c.getGradeId()));
    }

    @Override
    public List<ClassDTO> classesOfCurrentYear(Long tenantId) {
        Long yearId = currentYear(tenantId).id();
        return TenantContext.withTenant(tenantId, () -> {
            List<EduClass> classes = classMapper.selectList(Wrappers.<EduClass>lambdaQuery().eq(EduClass::getYearId, yearId)
                    .eq(EduClass::getClassStatus, "ACTIVE"));
            return listClasses(classes.stream().map(EduClass::getId).collect(Collectors.toSet()));
        });
    }

    @Override
    public List<ClassDTO> listClasses(Set<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        List<EduClass> classes = classMapper.selectByIds(classIds);
        Set<Long> gradeIds = classes.stream().map(EduClass::getGradeId).collect(Collectors.toSet());
        Map<Long, EduGrade> grades = gradeMapper.selectByIds(gradeIds).stream()
                .collect(Collectors.toMap(EduGrade::getId, Function.identity()));
        return classes.stream().map(c -> toDto(c, grades.get(c.getGradeId()))).toList();
    }

    @Override
    public List<StudentDTO> listStudentsByClass(Long classId) {
        return studentMapper.selectList(Wrappers.<EduStudent>lambdaQuery().eq(EduStudent::getClassId, classId)
                .eq(EduStudent::getStudentStatus, "ACTIVE").orderByAsc(EduStudent::getStudentNo))
                .stream().map(this::toDto).toList();
    }

    @Override
    public List<StudentDTO> listStudents(Set<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return List.of();
        }
        return studentMapper.selectByIds(studentIds).stream().map(this::toDto).toList();
    }

    @Override
    public Optional<StudentDTO> findStudentByNo(Long tenantId, String studentNo) {
        return TenantContext.withTenant(tenantId, () -> Optional.ofNullable(studentMapper.selectOne(
                Wrappers.<EduStudent>lambdaQuery().eq(EduStudent::getStudentNo, studentNo))).map(this::toDto));
    }

    @Override
    public StudentDTO getStudent(Long studentId) {
        return toDto(BizException.notNull(studentMapper.selectById(studentId), "学生"));
    }

    @Override
    public List<Long> parentUserIds(Long studentId) {
        return bindingMapper.selectList(Wrappers.<EduParentBinding>lambdaQuery()
                        .eq(EduParentBinding::getStudentId, studentId).eq(EduParentBinding::getBindStatus, "BOUND"))
                .stream().map(EduParentBinding::getParentUserId).distinct().toList();
    }

    @Override
    public Long studentUserId(Long studentId) {
        EduStudent s = studentMapper.selectById(studentId);
        return s == null ? null : s.getUserId();
    }

    private ClassDTO toDto(EduClass c, EduGrade g) {
        return new ClassDTO(c.getId(), c.getTenantId(), c.getYearId(), c.getGradeId(),
                g == null ? null : g.getGradeName(), c.getClassName(), c.getHeadTeacherIdentityId());
    }

    private StudentDTO toDto(EduStudent s) {
        return new StudentDTO(s.getId(), s.getTenantId(), s.getClassId(), s.getStudentNo(), s.getStudentName(),
                s.getGender(), s.getStudentStatus());
    }
}
