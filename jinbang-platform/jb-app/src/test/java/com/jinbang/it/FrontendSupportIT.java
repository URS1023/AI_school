package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** 前端方案第 7 章：开工前后端补齐的 6 处接口。 */
class FrontendSupportIT extends ApiTestSupport {

    private MockHttpServletResponse raw(String url) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andReturn().getResponse();
    }

    private long sheet(String token) throws Exception {
        return ok(post("/api/exam/sheets", token, Map.of("name", "单元测验", "subject", "MATH", "blocks", List.of(
                Map.of("no", "1", "type", "SINGLE", "objective", true, "fullScore", 5, "answer", "A"),
                Map.of("no", "2", "type", "SINGLE", "objective", true, "fullScore", 5, "answer", "B"),
                Map.of("no", "3", "type", "ANSWER", "objective", false, "fullScore", 12),
                Map.of("no", "4", "type", "ESSAY", "objective", false, "fullScore", 60, "minWords", 400))))).path("sheet").path("id").asLong();
    }

    /** 7.1 原卷图片：扫描端上传 → 识别回传存储键 → 教师取短时链接；链接可直接用于 img；其他班老师 403。 */
    @Test
    void answerImages() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        long sheetId = sheet(s.mathToken());
        long hw = ok(post("/api/exam/homeworks", s.mathToken(), Map.of("title", "测验", "subject", "MATH", "sheetId", sheetId,
                "classIds", List.of(s.class1())))).path("id").asLong();
        long batch = ok(post("/api/exam/scan/batches", s.mathToken(), Map.of("sourceType", "HOMEWORK", "sourceId", hw))).path("id").asLong();
        String up = mvc.perform(multipart("/api/exam/scan/batches/" + batch + "/images")
                        .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}))
                        .file(new MockMultipartFile("files", "b.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 2}))
                        .header("Authorization", "Bearer " + s.mathToken()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode keys = om.readTree(up).path("data");
        assertThat(keys.size()).isEqualTo(2);
        ok(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/exam/recognition/callback")
                .header("X-Recognition-Token", "test-token"), null, Map.of("batchId", batch, "reRecognize", false, "pages", List.of(
                Map.of("pageRef", "1", "images", List.of(keys.get(0).asText(), keys.get(1).asText()), "studentNo", s.stu1No(),
                        "answers", Map.of("1", "A"))))));
        long answerId = ok(get("/api/exam/homework/" + hw + "/marking/objective-matrix", s.mathToken())).get(0).path("answerId").asLong();
        JsonNode links = ok(get("/api/exam/answers/" + answerId + "/images", s.mathToken()));
        assertThat(links.size()).isEqualTo(2);
        MockHttpServletResponse img = raw(links.get(0).asText());   // 不带登录态，模拟 <img src>
        assertThat(img.getStatus()).isEqualTo(200);
        assertThat(img.getContentType()).isEqualTo("image/jpeg");
        assertThat(img.getHeader("Content-Disposition")).startsWith("inline");
        assertThat(get("/api/exam/answers/" + answerId + "/images", s.math2Token()).path("code").asInt()).isEqualTo(403);
    }

    /** 7.2 按 ID 取姓名：只返回有权限的人。 */
    @Test
    void namesRespectDataScope() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        long headUser = ok(get("/api/system/user/me", s.headToken())).path("userId").asLong();
        JsonNode r = ok(post("/api/school/names", s.mathToken(), Map.of("studentIds", List.of(s.stu1(), s.stu3()),
                "userIds", List.of(headUser))));
        assertThat(r.path("students").has(String.valueOf(s.stu1()))).isTrue();
        assertThat(r.path("students").has(String.valueOf(s.stu3()))).isFalse();
        assertThat(r.path("students").path(String.valueOf(s.stu1())).path("className").asText()).isEqualTo("高一1班");
        assertThat(r.path("users").path(String.valueOf(headUser)).asText()).isEqualTo("王班主任");
        // 游客看不到任何学校人员
        String visitor = registerVisitor(newPhone());
        JsonNode v = ok(post("/api/school/names", visitor, Map.of("studentIds", List.of(s.stu1()), "userIds", List.of(headUser))));
        assertThat(v.path("students").size()).isZero();
        assertThat(v.path("users").size()).isZero();
    }

    /** 7.3 答题卡 PDF：二维码 + 区域坐标；下载即冻结版本。 */
    @Test
    void sheetPdf() throws Exception {
        String t = SchoolFixture.create(this).mathToken();
        long sheetId = sheet(t);
        JsonNode pdf = ok(post("/api/exam/sheets/" + sheetId + "/pdf", t, null));
        assertThat(pdf.path("versionNo").asInt()).isEqualTo(1);
        assertThat(pdf.path("pages").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(pdf.path("regions").toString()).contains("STUDENT_NO").contains("3#score").contains("\"no\":\"4\"");
        MockHttpServletResponse file = raw(pdf.path("url").asText());
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(file.getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        JsonNode v2 = ok(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/exam/sheets/" + sheetId + "/blocks"), t, Map.of("blocks", List.of(
                Map.of("no", "1", "type", "SINGLE", "objective", true, "fullScore", 10, "answer", "A")))));
        assertThat(v2.path("version").path("versionNo").asInt()).isEqualTo(2);
    }

    /** 7.4 课件预览：PPTX 逐页成图，DOCX 转 HTML。 */
    @Test
    void materialPreview() throws Exception {
        String t = registerVisitor(newPhone());
        long ppt = ok(post("/api/prep/materials/blank", t, Map.of("name", "导入", "type", "PPTX"))).path("id").asLong();
        JsonNode p = ok(get("/api/prep/materials/" + ppt + "/preview", t));
        assertThat(p.path("type").asText()).isEqualTo("IMAGES");
        assertThat(raw(p.path("urls").get(0).asText()).getContentType()).isEqualTo("image/png");
        JsonNode plan = ok(post("/api/prep/lesson-plan", t, Map.of("topic", "函数的单调性")));
        JsonNode d = ok(get("/api/prep/materials/" + plan.path("id").asLong() + "/preview", t));
        assertThat(d.path("type").asText()).isEqualTo("HTML");
        assertThat(d.path("html").asText()).contains("函数的单调性 教案");
    }

    /** 7.5 本校教师精简列表；7.6 学生端「我是谁」。 */
    @Test
    void teachersAndPortalMe() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        JsonNode list = ok(get("/api/school/teachers/simple", s.mathToken()));
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.toString()).contains("****");
        assertThat(get("/api/school/teachers/simple", registerVisitor(newPhone())).path("code").asInt()).isEqualTo(403);

        String stuUser = ok(post("/api/school/students/" + s.stu1() + "/account", s.adminToken(), null)).asText();
        String stu = loginAndChangeDefault(stuUser, "123456", "Stud12345");
        JsonNode me = ok(get("/api/portal/me", stu));
        assertThat(me.path("role").asText()).isEqualTo("STUDENT");
        assertThat(me.path("students").get(0).path("name").asText()).isEqualTo("张三");
        assertThat(me.path("students").get(0).path("className").asText()).isEqualTo("高一1班");
        assertThat(get("/api/portal/me", s.mathToken()).path("code").asInt()).isEqualTo(403);
    }
}
