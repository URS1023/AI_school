package com.jinbang.common.datascope;

import com.jinbang.common.security.DataScope;

import java.util.Map;
import java.util.Set;

/**
 * 当前身份能看到的教学数据范围（PRD 8.2 R2）。
 * <ul>
 *   <li>任课教师：只能看「所教班级 × 所教学科」，即 {@link #classSubjects}</li>
 *   <li>班主任：本班全学科</li>
 *   <li>年级主任：本年级</li>
 *   <li>学科组长：本校本学科</li>
 *   <li>学校管理员、校领导：全校</li>
 *   <li>学生：只能看自己；家长：只能看已绑定的孩子（R7）</li>
 * </ul>
 */
public record VisibleScope(
        DataScope scope,
        long tenantId,
        /* 有全学科权限的班级（班主任的班、年级主任的年级下所有班）。 */
        Set<Long> fullClassIds,
        /* 只有部分学科权限的班级：classId → 学科集合（任课教师）。 */
        Map<Long, Set<String>> classSubjects,
        /* 学科组长：全校这些学科。 */
        Set<String> schoolSubjects,
        /* 学生/家长：可见的学生 ID。 */
        Set<Long> studentIds) {

    public static VisibleScope none(long tenantId) {
        return new VisibleScope(DataScope.SELF, tenantId, Set.of(), Map.of(), Set.of(), Set.of());
    }

    public boolean isSchoolWide() {
        return scope.level() <= DataScope.SCHOOL.level();
    }

    /** 能否看某班某学科的数据。subject 为 null 表示要求全学科权限。 */
    public boolean canSee(Long classId, String subject) {
        if (isSchoolWide()) {
            return true;
        }
        if (fullClassIds.contains(classId)) {
            return true;
        }
        if (subject == null) {
            return false;
        }
        if (schoolSubjects.contains(subject)) {
            return true;
        }
        Set<String> subs = classSubjects.get(classId);
        return subs != null && subs.contains(subject);
    }

    public boolean canSeeStudent(Long studentId) {
        return studentIds.contains(studentId);
    }

    /** 能看到的全部班级（不含按学科组的全校范围）。 */
    public Set<Long> anyClassIds() {
        java.util.Set<Long> all = new java.util.HashSet<>(fullClassIds);
        all.addAll(classSubjects.keySet());
        return all;
    }
}
