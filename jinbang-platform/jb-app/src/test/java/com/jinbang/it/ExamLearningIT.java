package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.api.account.AccountApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PRD 第 17 章：AC-01 ~ AC-08、AC-18、AC-19，以及学生端 / 家长端可见性。 */
class ExamLearningIT extends ApiTestSupport {
    @Autowired
    AccountApi accountApi;

    /** 2 分客观题 + 12 分解答题（AC-07 需要分值不同）。 */
    private static final List<Map<String, Object>> BLOCKS = List.of(
            Map.of("no", "1", "type", "SINGLE", "objective", true, "fullScore", 2, "answer", "A", "options", 4),
            Map.of("no", "2", "type", "ANSWER", "objective", false, "fullScore", 12));

    private JsonNode callback(Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/exam/recognition/callback").header("X-Recognition-Token", "test-token"), null, body);
    }

    private static Map<String, Object> page(String ref, String no, String objective, Integer score) {
        java.util.HashMap<String, Object> p = new java.util.HashMap<>();
        p.put("pageRef", ref);
        p.put("images", List.of(ref + "-a.jpg", ref + "-b.jpg"));
        p.put("studentNo", no);
        p.put("answers", objective == null ? Map.of() : Map.of("1", objective));
        p.put("scores", score == null ? Map.of() : Map.of("2", score));
        return p;
    }

    private long sheet(String token) throws Exception {
        return ok(post("/api/exam/sheets", token, Map.of("name", "数学作业卡", "subject", "MATH", "markMode", "MARK_THEN_SCAN",
                "blocks", BLOCKS))).path("sheet").path("id").asLong();
    }

    @Test
    void homeworkScanMarkingAndLearning() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String math = s.mathToken();
        long sheetId = sheet(math);

        // AC-01：学年取学校当前学年；没有任教关系的班不能布置
        assertThat(post("/api/exam/homeworks", math, Map.of("title", "函数作业", "subject", "MATH", "sheetId", sheetId,
                "classIds", List.of(s.class2()))).path("code").asInt()).isEqualTo(403);
        JsonNode hw = ok(post("/api/exam/homeworks", math, Map.of("title", "函数作业", "subject", "MATH", "sheetId", sheetId,
                "classIds", List.of(s.class1()))));
        assertThat(hw.path("yearId").asLong()).isEqualTo(s.yearId());
        long hwId = hw.path("id").asLong();

        long batch = ok(post("/api/exam/scan/batches", math, Map.of("sourceType", "HOMEWORK", "sourceId", hwId)))
                .path("id").asLong();
        // 无 token 的回调被拒绝
        assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/exam/recognition/callback"), null, Map.of("batchId", batch, "pages", List.of()))
                .path("code").asInt()).isEqualTo(403);
        JsonNode ing = ok(callback(Map.of("batchId", batch, "reRecognize", false, "pages", List.of(
                page("p1", s.stu1No(), "A", 70),     // 识别出 70 分 > 12 分满分 → 待复核（AC-18）
                page("p2", null, "B", 6),            // 学号缺失 → 异常卷
                page("p3", "999999", "A", 3)))));    // 学号不存在 → 异常卷
        assertThat(ing.path("recognized").asInt()).isEqualTo(1);
        assertThat(ing.path("exceptions").asInt()).isEqualTo(2);

        // AC-18：人工录入 70 分被拒绝
        JsonNode exceptions = ok(get("/api/exam/homework/" + hwId + "/exceptions", math));
        assertThat(exceptions.size()).isEqualTo(2);
        JsonNode next = ok(get("/api/exam/homework/" + hwId + "/marking/next?itemNo=2", math));
        assertThat(next.path("reviewReason").asText()).contains("超出本题满分");
        long a1 = next.path("answerId").asLong();
        assertThat(post("/api/exam/marking/submit", math, Map.of("answerId", a1, "itemNo", "2", "score", 70))
                .path("code").asInt()).isEqualTo(3002);
        ok(post("/api/exam/marking/submit", math, Map.of("answerId", a1, "itemNo", "2", "score", 12)));

        // AC-19：学号缺失的卷分配给李四 → 已提交人数重算
        long missing = 0;
        for (JsonNode e : exceptions) {
            if ("STUDENT_MISSING".equals(e.path("exceptionReason").asText())) {
                missing = e.path("id").asLong();
            }
        }
        assertThat(post("/api/exam/answers/" + missing + "/assign", math, Map.of("studentId", s.stu3())).path("code").asInt())
                .isEqualTo(400); // 不在名单中
        ok(post("/api/exam/answers/" + missing + "/assign", math, Map.of("studentId", s.stu2())));
        JsonNode stats = ok(get("/api/exam/homework/" + hwId + "/stats", math));
        assertThat(stats.path("expected").asInt()).isEqualTo(2);
        assertThat(stats.path("submitted").asInt()).isEqualTo(2);
        assertThat(stats.path("exceptions").asInt()).isEqualTo(1);
        assertThat(stats.path("marked").asInt()).isEqualTo(2);

        // AC-07：班级平均得分率 = 实得总分 ÷ 应得满分 = (14 + 6) / 28
        JsonNode rep = ok(get("/api/learning/homework/" + hwId + "/report", math)).path("report");
        assertThat(rep.path("avgRate").asDouble()).isEqualTo(0.7143);
        assertThat(rep.path("bands").path("优秀").asInt()).isEqualTo(1);
        assertThat(rep.path("bands").path("不合格").asInt()).isEqualTo(1);
        for (JsonNode row : rep.path("students")) {
            assertThat(row.path("rate").asDouble()).isBetween(0.0, 1.0);
        }
        JsonNode detail = ok(get("/api/learning/homework/" + hwId + "/students/" + s.stu2(), math));
        assertThat(detail.path("rate").asDouble()).isLessThanOrEqualTo(1.0);
        for (JsonNode t : detail.path("typeLoss")) {
            assertThat(t.path("lost").asDouble()).isGreaterThanOrEqualTo(0);
        }

        // AC-02：张三重扫一次，识别出 3 分 → 新卷为当前卷，但人工 12 分不被覆盖；旧卷进重复列表，可换回
        JsonNode dup = ok(callback(Map.of("batchId", batch, "reRecognize", false, "pages", List.of(page("p4", s.stu1No(), "A", 3)))));
        assertThat(dup.path("duplicates").asInt()).isEqualTo(1);
        JsonNode dups = ok(get("/api/exam/homework/" + hwId + "/duplicates", math));
        assertThat(dups.size()).isEqualTo(1);
        JsonNode rep2 = ok(get("/api/learning/homework/" + hwId + "/report", math)).path("report");
        assertThat(rep2.path("avgRate").asDouble()).isEqualTo(0.7143);

        // AC-03：整批重新识别：只更新客观题；人工分保留，冲突提示
        JsonNode rr = ok(callback(Map.of("batchId", batch, "reRecognize", true, "pages", List.of(page("p4", s.stu1No(), "B", 5)))));
        assertThat(rr.path("conflicts").size()).isEqualTo(1);
        assertThat(rr.path("conflicts").get(0).asText()).contains("已保留人工分");
        JsonNode matrix = ok(get("/api/exam/homework/" + hwId + "/marking/objective-matrix", math));
        boolean stu1Total = false;
        for (JsonNode row : matrix) {
            if (row.path("studentId").asLong() == s.stu1()) {
                assertThat(row.path("total").asDouble()).isEqualTo(12.0); // 客观题改成 B 判错，人工 12 分保留
                stu1Total = true;
            }
        }
        assertThat(stu1Total).isTrue();

        // AC-04：答题卡下载后修改 → 生成 v2；用 v1 二维码扫描仍按 v1 判分
        ok(post("/api/exam/sheets/" + sheetId + "/download", math, null));
        JsonNode v2 = ok(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/exam/sheets/" + sheetId + "/blocks"), math, Map.of("blocks", List.of(
                Map.of("no", "1", "type", "SINGLE", "objective", true, "fullScore", 4, "answer", "A"),
                Map.of("no", "2", "type", "ANSWER", "objective", false, "fullScore", 12)))));
        assertThat(v2.path("version").path("versionNo").asInt()).isEqualTo(2);
        Map<String, Object> pg = new java.util.HashMap<>(page("p5", s.stu2No(), "A", 12));
        pg.put("qr", "JBSHEET:" + sheetId + ":1");
        ok(callback(Map.of("batchId", batch, "reRecognize", false, "pages", List.of(pg))));
        JsonNode m2 = ok(get("/api/exam/homework/" + hwId + "/marking/objective-matrix", math));
        for (JsonNode row : m2) {
            if (row.path("studentId").asLong() == s.stu2()) {
                assertThat(row.path("total").asDouble()).isEqualTo(14.0); // 按 v1：客观题 2 分
            }
        }

        // 批阅完成 → 错题沉淀；教师看到本班数学错题；AI 变式题按出题计费
        ok(post("/api/exam/homeworks/" + hwId + "/finish", math, null));
        JsonNode wrong = ok(get("/api/learning/wrong?classIds=" + s.class1(), math));
        assertThat(wrong.path("total").asLong()).isGreaterThanOrEqualTo(1);
        long wrongId = wrong.path("rows").get(0).path("id").asLong();
        assertThat(post("/api/learning/wrong/" + wrongId + "/variants", math, null).path("code").asInt()).isEqualTo(2001);
        long mathUser = ok(get("/api/system/user/me", math)).path("userId").asLong();
        accountApi.grantCoins(mathUser, 100, "ADJUST", "test-" + mathUser, "测试");
        JsonNode variants = ok(post("/api/learning/wrong/" + wrongId + "/variants", math, null));
        assertThat(variants.path("charged").asLong()).isEqualTo(4);
        JsonNode book = ok(post("/api/learning/wrong-books", math, Map.of("subject", "MATH", "wrongIds", List.of(wrongId))));
        assertThat(book.path("bookName").asText()).endsWith("错题本");
        // 2 班数学老师看不到 1 班错题
        assertThat(get("/api/learning/wrong?classIds=" + s.class1(), s.math2Token()).path("code").asInt()).isEqualTo(403);
    }

    @Test
    void examAuditPublishCorrectionAndWithdraw() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String admin = s.adminToken();
        long sheetId = sheet(admin);
        JsonNode exam = ok(post("/api/exam/exams", admin, Map.of("name", "期中数学", "subject", "MATH", "sheetId", sheetId,
                "gradeId", s.gradeId(), "classIds", List.of(s.class1(), s.class2()))));
        long examId = exam.path("id").asLong();
        assertThat(ok(get("/api/exam/exams/" + examId + "/examinees", admin)).size()).isEqualTo(3);
        // 草稿状态不能扫描
        assertThat(post("/api/exam/scan/batches", admin, Map.of("sourceType", "EXAM", "sourceId", examId)).path("code").asInt())
                .isEqualTo(3001);
        ok(post("/api/exam/exams/" + examId + "/flow", admin, Map.of("action", "READY")));
        long batch = ok(post("/api/exam/scan/batches", admin, Map.of("sourceType", "EXAM", "sourceId", examId))).path("id").asLong();
        ok(callback(Map.of("batchId", batch, "reRecognize", false, "pages", List.of(
                page("e1", s.stu1No(), "A", 10), page("e2", s.stu3No(), "C", 4)))));
        // AC-08：李四缺考
        ok(post("/api/exam/exams/" + examId + "/examinees/status", admin, Map.of("studentId", s.stu2(), "status", "ABSENT")));
        ok(post("/api/exam/exams/" + examId + "/flow", admin, Map.of("action", "COMPLETE_SCAN")));
        ok(post("/api/exam/exams/" + examId + "/flow", admin, Map.of("action", "FINISH_MARK")));
        JsonNode rep = ok(get("/api/learning/exam/" + examId + "/report", admin)).path("report");
        assertThat(rep.path("counts").path("valid").asInt()).isEqualTo(2);
        assertThat(rep.path("counts").path("absent").asInt()).isEqualTo(1);
        assertThat(rep.path("avgScore").asDouble()).isEqualTo(8.0); // (12 + 4) / 2，缺考不计入

        // 学生、家长账号
        String stuUser = ok(post("/api/school/students/" + s.stu1() + "/account", admin, null)).asText();
        String stu = loginAndChangeDefault(stuUser, "123456", "Stud12345");
        String parent1 = registerVisitor(newPhone());
        ok(post("/api/school/parent/bind", parent1, Map.of("schoolId", s.tenantId(), "studentNo", s.stu1No(),
                "studentName", "张三", "inviteCode", s.class1ParentCode())));
        JsonNode pid = ok(get("/api/auth/identities", parent1)).path("identities");
        for (JsonNode i : pid) {
            if ("PARENT".equals(i.path("type").asText())) {
                ok(post("/api/auth/identity/switch", parent1, Map.of("identityId", i.path("identityId").asLong())));
            }
        }

        // D2：需要审核的考试不能直接发布
        assertThat(post("/api/exam/exams/" + examId + "/publish", admin, Map.of()).path("code").asInt()).isEqualTo(3001);
        ok(post("/api/exam/exams/" + examId + "/flow", admin, Map.of("action", "SUBMIT_AUDIT")));
        ok(post("/api/exam/exams/" + examId + "/flow", admin, Map.of("action", "APPROVE")));
        // 分批发布：只发 1 班
        ok(post("/api/exam/exams/" + examId + "/publish", admin, Map.of("classIds", List.of(s.class1()))));
        JsonNode mine = resultOf(ok(get("/api/portal/results", stu)), examId);
        assertThat(mine.path("score").asDouble()).isEqualTo(12.0);
        assertThat(resultOf(ok(get("/api/portal/results", parent1)), examId).path("state").asText()).isEqualTo("已发布");
        // 学生看不到别人
        assertThat(get("/api/portal/results?studentId=" + s.stu3(), stu).path("code").asInt()).isEqualTo(403);

        // AC-05：发布后改分必须写原因 → 通知学生和家长
        long a1 = 0;
        for (JsonNode row : ok(get("/api/exam/exam/" + examId + "/marking/objective-matrix", admin))) {
            if (row.path("studentId").asLong() == s.stu1()) {
                a1 = row.path("answerId").asLong();
            }
        }
        assertThat(post("/api/exam/marking/submit", admin, Map.of("answerId", a1, "itemNo", "2", "score", 9)).path("code").asInt())
                .isEqualTo(400);
        ok(post("/api/exam/marking/submit", admin, Map.of("answerId", a1, "itemNo", "2", "score", 9, "reason", "复核第 2 题")));
        JsonNode msgs = ok(get("/api/messages?category=SCORE", parent1)).path("rows");
        assertThat(msgs.toString()).contains("成绩更正").contains("12 → 11");
        assertThat(resultOf(ok(get("/api/portal/results", stu)), examId).path("score").asDouble()).isEqualTo(11.0);

        // AC-06：撤回后立即看不到，补发撤回通知
        ok(post("/api/exam/exams/" + examId + "/withdraw", admin, null));
        assertThat(resultOf(ok(get("/api/portal/results", stu)), examId).path("state").asText()).isEqualTo("成绩未发布");
        assertThat(ok(get("/api/messages?category=SCORE", parent1)).path("rows").toString()).contains("成绩暂时撤回");
    }

    private static JsonNode resultOf(JsonNode list, long examId) {
        for (JsonNode r : list) {
            if ("EXAM".equals(r.path("sourceType").asText()) && r.path("sourceId").asLong() == examId) {
                return r;
            }
        }
        throw new AssertionError("no result for exam " + examId + " in " + list);
    }
}
