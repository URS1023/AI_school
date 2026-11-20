package com.jinbang.api.system;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 学校基础数据契约：学年、年级、班级、学生、家长。供考试、学情、作业模块调用。 */
public interface SchoolApi {

    /** 学校当前学年（D1：学年一律取学校设置，不允许前端写死）。 */
    AcademicYearDTO currentYear(Long tenantId);

    ClassDTO getClass(Long classId);

    List<ClassDTO> listClasses(Set<Long> classIds);

    List<StudentDTO> listStudentsByClass(Long classId);

    List<StudentDTO> listStudents(Set<Long> studentIds);

    Optional<StudentDTO> findStudentByNo(Long tenantId, String studentNo);

    StudentDTO getStudent(Long studentId);

    /** 学生绑定的家长用户 ID（用于成绩更正、撤回通知）。 */
    List<Long> parentUserIds(Long studentId);

    /** 学生自己的登录用户 ID（没有开通学生账号时为 null）。 */
    Long studentUserId(Long studentId);

    record AcademicYearDTO(Long id, Long tenantId, String name, int startYear) {
    }

    record ClassDTO(Long id, Long tenantId, Long yearId, Long gradeId, String gradeName, String name,
                    Long headTeacherIdentityId) {
    }

    record StudentDTO(Long id, Long tenantId, Long classId, String studentNo, String name, String gender,
                      String status) {
    }
}
