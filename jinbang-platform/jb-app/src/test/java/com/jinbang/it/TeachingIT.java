package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeachingIT extends ApiTestSupport {

    private JsonNode put(String path, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), token, body);
    }

    /** PRD 4.1.2：大纲可编辑（P0）；生成 PPT 存入目录；+20 经验 +10 金币，每次备课只奖励一次；主站智能备课不扣费。 */
    @Test
    void smartPrepWithEditableOutlineAndReward() throws Exception {
        String t = registerVisitor(newPhone());
        long cat = ok(post("/api/prep/catalogs", t, Map.of("name", "第1课 沁园春·长沙", "subject", "CHINESE"))).path("id").asLong();
        JsonNode v = ok(post("/api/prep/smart", t, Map.of("catalogId", cat, "topic", "沁园春·长沙", "course", "沁园春·长沙",
                "edition", "统编版")));
        long id = v.path("prep").path("id").asLong();
        ObjectNode outline = (ObjectNode) v.path("outline");
        ArrayNode chapters = (ArrayNode) outline.path("chapters");
        chapters.remove(0);
        chapters.addObject().put("title", "拓展阅读").putArray("sections").addObject().put("title", "毛泽东诗词")
                .putArray("points").add("《七律·长征》");
        JsonNode edited = ok(put("/api/prep/smart/" + id + "/outline", t, outline));
        assertThat(edited.path("outline").toString()).contains("拓展阅读").doesNotContain("情境引入");
        JsonNode g = ok(post("/api/prep/smart/" + id + "/generate", t, Map.of("template", "red_china")));
        assertThat(g.path("material").path("catalogId").asLong()).isEqualTo(cat);
        assertThat(g.path("material").path("fileName").asText()).isEqualTo("沁园春·长沙.pptx");
        JsonNode acc = ok(get("/api/account/me", t));
        assertThat(acc.path("balance").asLong()).isEqualTo(510);
        assertThat(acc.path("exp").asLong()).isEqualTo(22);
        ok(post("/api/prep/smart/" + id + "/generate", t, Map.of("template", "urban_blue")));
        assertThat(ok(get("/api/account/me", t)).path("balance").asLong()).isEqualTo(510);
    }

    @Test
    void lessonPlanAndAiPptSyncAndMaterialOps() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode plan = ok(post("/api/prep/lesson-plan", t, Map.of("topic", "函数的单调性")));
        assertThat(plan.path("fileType").asText()).isEqualTo("DOCX");
        assertThat(ok(get("/api/account/me", t)).path("balance").asLong()).isEqualTo(490);

        // AI 平台生成的课件自动进「AI 生成」目录（PRD 7.4 ➕P0）
        JsonNode o = ok(post("/api/ai/ppt/outline", t, Map.of("topic", "牛顿第一定律")));
        JsonNode outline = om.readTree(o.path("items").get(0).path("output").asText());
        ok(post("/api/ai/ppt/generate", t, Map.of("outline", outline)));
        JsonNode cats = ok(get("/api/prep/catalogs", t));
        long ai = 0;
        for (JsonNode c : cats) {
            if ("AI 生成".equals(c.path("catalogName").asText())) {
                ai = c.path("id").asLong();
            }
        }
        assertThat(ok(get("/api/prep/materials?catalogId=" + ai, t)).size()).isEqualTo(2);

        long blank = ok(post("/api/prep/materials/blank", t, Map.of("name", "空白课件", "type", "PPTX"))).path("id").asLong();
        long copy = ok(post("/api/prep/materials/" + blank + "/copy", t, null)).path("id").asLong();
        ok(post("/api/prep/materials/" + copy + "/recycle", t, null));
        assertThat(ok(get("/api/prep/materials?recycle=true", t)).size()).isEqualTo(1);
        ok(post("/api/prep/materials/" + copy + "/recycle?deleted=false", t, null));
        assertThat(ok(get("/api/prep/materials?recycle=true", t)).size()).isZero();
        assertThat(ok(get("/api/prep/materials/" + blank + "/versions", t)).size()).isEqualTo(1);
        // 别人的资料不可见
        String other = registerVisitor(newPhone());
        assertThat(post("/api/prep/materials/" + blank + "/download", other, null).path("code").asInt()).isEqualTo(404);
    }

    @Test
    void collectivePrep() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        long headUser = ok(get("/api/system/user/me", s.headToken())).path("userId").asLong();
        long mathUser = ok(get("/api/system/user/me", s.mathToken())).path("userId").asLong();
        assertThat(post("/api/collective/themes", s.mathToken(), Map.of("title", "x", "memberUserIds", List.of(headUser)))
                .path("code").asInt()).isEqualTo(403);
        long theme = ok(post("/api/collective/themes", s.adminToken(), Map.of("title", "高一函数单元集体备课",
                "subject", "MATH", "memberUserIds", List.of(headUser, mathUser)))).path("id").asLong();
        long mat = ok(post("/api/prep/materials/blank", s.adminToken(), Map.of("name", "主备稿", "type", "DOCX"))).path("id").asLong();
        ok(post("/api/collective/themes/" + theme + "/main-draft", s.adminToken(), Map.of("materialId", mat)));
        ok(post("/api/collective/themes/" + theme + "/comments", s.mathToken(), Map.of("content", "例题难度可以再分层")));
        assertThat(post("/api/collective/themes/" + theme + "/comments", s.math2Token(), Map.of("content", "旁听"))
                .path("code").asInt()).isEqualTo(403);
        JsonNode second = ok(post("/api/collective/themes/" + theme + "/second-prep", s.mathToken(), Map.of()));
        assertThat(second.path("fileName").asText()).isEqualTo("主备稿.docx");
        JsonNode stats = ok(get("/api/collective/themes/" + theme + "/stats", s.adminToken()));
        assertThat(stats.path(String.valueOf(mathUser)).asInt()).isEqualTo(1);
    }

    /** 杏坛发布前敏感词审核（P0）；学海共渡学生帖人工审核后才可见，并按学校隔离。 */
    @Test
    void communityModeration() throws Exception {
        String teacher = registerVisitor(newPhone());
        ok(post("/api/community/posts", teacher, Map.of("board", "XINGTAN", "title", "分享", "content", "这节课的导入设计很有效")));
        assertThat(post("/api/community/posts", teacher, Map.of("board", "XINGTAN", "content", "代考联系我")).path("code").asInt())
                .isEqualTo(4001);

        SchoolFixture s = SchoolFixture.create(this);
        String stuUser = ok(post("/api/school/students/" + s.stu1() + "/account", s.adminToken(), null)).asText();
        String stu = loginAndChangeDefault(stuUser, "123456", "Stud12345");
        long post = ok(post("/api/community/posts", stu, Map.of("board", "STUDENT_FORUM", "title", "求助", "content",
                "这道导数题怎么做"))).path("id").asLong();
        assertThat(ok(get("/api/community/posts?board=STUDENT_FORUM", s.headToken())).path("total").asLong()).isZero();
        String admin = adminToken();
        long auditId = 0;
        for (JsonNode a : ok(get("/api/audit/pending?bizType=POST", admin)).path("rows")) {
            if (a.path("bizId").asLong() == post) {
                auditId = a.path("id").asLong();
            }
        }
        ok(post("/api/audit/" + auditId + "/decide", admin, Map.of("approve", true)));
        assertThat(ok(get("/api/community/posts?board=STUDENT_FORUM", s.headToken())).path("total").asLong()).isEqualTo(1);
        ok(post("/api/community/posts/" + post + "/comments", s.headToken(), Map.of("content", "先求导再看单调区间")));
        assertThat(ok(get("/api/messages/unread-count", stu)).asLong()).isGreaterThanOrEqualTo(1);
        SchoolFixture other = SchoolFixture.create(this);
        assertThat(ok(get("/api/community/posts?board=STUDENT_FORUM", other.headToken())).path("total").asLong()).isZero();
        // 游客没有学生论坛权限
        assertThat(get("/api/community/posts?board=STUDENT_FORUM", teacher).path("code").asInt()).isEqualTo(403);
    }

    /** 教辅页面模板库（P0）：扫描教辅按页面二维码定位题目；一个学生多页合并统计；数据中心统计扫描量。 */
    @Test
    void workbookScanAndDataCenter() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String admin = adminToken();
        String book = "MATH-G10-" + s.tenantId();
        for (int pageNo : List.of(12, 13)) {
            ok(post("/api/admin/workbook/pages", admin, Map.of("bookCode", book, "bookName", "高一数学课时作业",
                    "subject", "MATH", "pageNo", pageNo, "blocks", List.of(
                            Map.of("no", "1", "type", "SINGLE", "objective", true, "fullScore", 5, "answer", "C"),
                            Map.of("no", "2", "type", "ANSWER", "objective", false, "fullScore", 10)))));
        }
        long hw = ok(post("/api/exam/homeworks", s.mathToken(), Map.of("title", "课时作业 P12-13", "subject", "MATH",
                "scanType", "WORKBOOK", "classIds", List.of(s.class1())))).path("id").asLong();
        long batch = ok(post("/api/exam/scan/batches", s.mathToken(), Map.of("sourceType", "HOMEWORK", "sourceId", hw,
                "scanType", "WORKBOOK"))).path("id").asLong();
        Map<String, Object> p12 = Map.of("pageRef", "w1", "images", List.of("w1.jpg"), "qr", "JBBOOK:" + book + ":12",
                "studentNo", s.stu1No(), "answers", Map.of("1", "C"), "scores", Map.of("2", 8));
        Map<String, Object> p13 = Map.of("pageRef", "w2", "images", List.of("w2.jpg"), "qr", "JBBOOK:" + book + ":13",
                "studentNo", s.stu1No(), "answers", Map.of("1", "A"), "scores", Map.of("2", 10));
        JsonNode r = ok(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/exam/recognition/callback")
                .header("X-Recognition-Token", "test-token"), null, Map.of("batchId", batch, "reRecognize", false,
                "pages", List.of(p12, p13))));
        assertThat(r.path("duplicates").asInt()).isZero();
        JsonNode stats = ok(get("/api/exam/homework/" + hw + "/stats", s.mathToken()));
        assertThat(stats.path("submitted").asInt()).isEqualTo(1);
        assertThat(stats.path("marked").asInt()).isEqualTo(1);
        JsonNode rep = ok(get("/api/learning/homework/" + hw + "/report", s.mathToken())).path("report");
        assertThat(rep.path("fullScore").asDouble()).isEqualTo(30.0);
        assertThat(rep.path("avgScore").asDouble()).isEqualTo(23.0); // 5 + 8 + 0 + 10

        JsonNode dc = ok(get("/api/exam/datacenter/scan", s.adminToken()));
        assertThat(dc.path("daily").get(0).path("images").asLong()).isEqualTo(2);
        assertThat(dc.path("teachers").get(0).path("works").asInt()).isEqualTo(1);
    }
}
