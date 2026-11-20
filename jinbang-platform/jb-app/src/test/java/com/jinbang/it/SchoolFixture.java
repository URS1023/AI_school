package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 一所演示学校：当前学年 2026，高一 1 班 / 2 班；
 * 1 班班主任（兼语文任课）、1 班数学任课教师、2 班数学任课教师；1 班 2 名学生，2 班 1 名学生。
 */
public record SchoolFixture(long tenantId, String inviteCode, String adminToken, long yearId, long gradeId,
                            long class1, long class2, String class1ParentCode,
                            String headToken, long headIdentity, String headPhone, String mathToken, long mathIdentity,
                            String math2Token, long math2Identity,
                            long stu1, long stu2, long stu3, String stu1No, String stu2No, String stu3No) {

    static SchoolFixture create(ApiTestSupport t) throws Exception {
        String admin = t.adminToken();
        String adminPhone = ApiTestSupport.newPhone();
        JsonNode school = t.ok(t.post("/api/admin/tenants", admin, Map.of("name", "测试中学" + adminPhone.substring(6),
                "periods", "1", "adminName", "校管理员", "adminPhone", adminPhone)));
        long tenantId = school.path("id").asLong();
        String sa = t.loginAndChangeDefault(adminPhone, "123456", "School1234");
        long yearId = t.ok(t.post("/api/school/years", sa, Map.of("startYear", 2026, "current", true))).path("id").asLong();
        long gradeId = t.ok(t.post("/api/school/grades", sa, Map.of("yearId", yearId, "level", 10))).path("id").asLong();
        JsonNode c1 = t.ok(t.post("/api/school/classes", sa, Map.of("gradeId", gradeId, "name", "高一1班")));
        long class2 = t.ok(t.post("/api/school/classes", sa, Map.of("gradeId", gradeId, "name", "高一2班"))).path("id").asLong();
        long class1 = c1.path("id").asLong();

        String headPhone = ApiTestSupport.newPhone();
        long headIdentity = t.ok(t.post("/api/school/teachers", sa, Map.of("name", "王班主任", "phone", headPhone,
                "subjects", "CHINESE", "rels", List.of(
                        Map.of("relType", "HEAD_TEACHER", "classId", class1),
                        Map.of("relType", "SUBJECT_TEACHER", "classId", class1, "subject", "CHINESE")))))
                .path("identityId").asLong();
        String mathPhone = ApiTestSupport.newPhone();
        long mathIdentity = t.ok(t.post("/api/school/teachers", sa, Map.of("name", "李数学", "phone", mathPhone,
                "subjects", "MATH", "rels", List.of(Map.of("relType", "SUBJECT_TEACHER", "classId", class1,
                        "subject", "MATH"))))).path("identityId").asLong();
        String math2Phone = ApiTestSupport.newPhone();
        long math2Identity = t.ok(t.post("/api/school/teachers", sa, Map.of("name", "赵数学", "phone", math2Phone,
                "subjects", "MATH", "rels", List.of(Map.of("relType", "SUBJECT_TEACHER", "classId", class2,
                        "subject", "MATH"))))).path("identityId").asLong();

        String base = String.valueOf(tenantId % 1000);
        String no1 = String.format("%06d", Math.abs((tenantId * 7 + 1) % 1_000_000));
        String no2 = String.format("%06d", Math.abs((tenantId * 7 + 2) % 1_000_000));
        String no3 = String.format("%06d", Math.abs((tenantId * 7 + 3) % 1_000_000));
        long s1 = t.ok(t.post("/api/school/students", sa, Map.of("studentNo", no1, "name", "张三", "gender", "男",
                "classId", class1))).path("id").asLong();
        long s2 = t.ok(t.post("/api/school/students", sa, Map.of("studentNo", no2, "name", "李四", "gender", "女",
                "classId", class1))).path("id").asLong();
        long s3 = t.ok(t.post("/api/school/students", sa, Map.of("studentNo", no3, "name", "王五", "gender", "男",
                "classId", class2))).path("id").asLong();

        String head = t.loginAndChangeDefault(headPhone, "123456", "Head12345");
        String math = t.loginAndChangeDefault(mathPhone, "123456", "Math12345");
        String math2 = t.loginAndChangeDefault(math2Phone, "123456", "Math12345");
        return new SchoolFixture(tenantId, school.path("inviteCode").asText(), sa, yearId, gradeId, class1, class2,
                c1.path("parentInviteCode").asText(), head, headIdentity, headPhone, math, mathIdentity, math2, math2Identity,
                s1, s2, s3, no1, no2, no3);
    }
}
