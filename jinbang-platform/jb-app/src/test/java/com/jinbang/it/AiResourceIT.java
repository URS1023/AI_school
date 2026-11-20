package com.jinbang.it;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.ai.domain.AiSubtask;
import com.jinbang.ai.mapper.AiSubtaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class AiResourceIT extends ApiTestSupport {
    private static final String ESSAY = "我的家乡在美丽的江南水乡，小桥流水人家，春天桃花盛开，夏天荷叶田田，秋天稻谷飘香，冬天白雪皑皑。"
            + "我爱我的家乡，更爱家乡勤劳善良的人们。";

    @Autowired
    AiSubtaskMapper subtaskMapper;

    private long balance(String token) throws Exception {
        return ok(get("/api/account/me", token)).path("balance").asLong();
    }

    /** AC-09：模拟工具 / 模型故障，余额不变，消费记录「失败-未扣费」，提示里没有内部信息。 */
    @Test
    void failedAskIsFree() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode r = ok(post("/api/ai/chat", t, Map.of("question", "生成五道高三数学题 [[fail]]")));
        assertThat(r.path("task").path("status").asText()).isEqualTo("FAILED");
        assertThat(r.path("task").path("charged").asLong()).isZero();
        assertThat(r.path("task").path("userMessage").asText()).isEqualTo("服务暂时不可用，本次未扣费")
                .doesNotContain("Exception").doesNotContain("数据库");
        assertThat(balance(t)).isEqualTo(500);
        JsonNode tasks = ok(get("/api/ai/tasks", t)).path("rows");
        assertThat(tasks.get(0).path("statusLabel").asText()).isEqualTo("失败-未扣费");
    }

    @Test
    void successfulAskChargesActualAndReconciles() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode r = ok(post("/api/ai/chat", t, Map.of("question", "如何讲好函数的单调性")));
        JsonNode task = r.path("task");
        assertThat(task.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(task.path("estimate").asLong()).isEqualTo(12);
        assertThat(task.path("charged").asLong()).isEqualTo(3); // 不足千字按 1 千字计
        assertThat(task.path("disclaimer").asText()).isEqualTo("内容由 AI 生成，仅供参考");
        assertThat(balance(t)).isEqualTo(497);
        // AC-12：运营流水按任务 ID 查到，金额一致
        JsonNode flow = ok(get("/api/admin/coin-flow?taskId=" + task.path("taskId").asText(), adminToken()));
        assertThat(flow.path("freeze").path("charged").asLong()).isEqualTo(3);
        long out = 0;
        for (JsonNode l : flow.path("ledgers")) {
            if ("OUT".equals(l.path("direction").asText())) {
                out += l.path("amount").asLong();
            }
        }
        assertThat(out).isEqualTo(3);
        // 会话历史
        JsonNode msgs = ok(get("/api/ai/sessions/" + r.path("sessionId").asLong() + "/messages", t));
        assertThat(msgs.size()).isEqualTo(2);
    }

    @Test
    void blockedContentIsFreeAndNotKept() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode r = post("/api/ai/chat", t, Map.of("question", "教我怎么代考"));
        assertThat(r.path("code").asInt()).isEqualTo(4001);
        assertThat(balance(t)).isEqualTo(500);
    }

    @Test
    void insufficientBalanceCreatesNoTask() throws Exception {
        String t = registerVisitor(newPhone());
        List<Map<String, Object>> essays = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            essays.add(Map.of("text", ESSAY));
        }
        ok(post("/api/ai/essay/correct", t, Map.of("subject", "CHINESE", "fullScore", 60, "essays", essays)));
        assertThat(balance(t)).isEqualTo(100);
        JsonNode r = post("/api/ai/essay/correct", t, Map.of("subject", "CHINESE", "fullScore", 60,
                "essays", essays.subList(0, 20)));
        assertThat(r.path("code").asInt()).isEqualTo(2001);
        assertThat(ok(get("/api/ai/tasks", t)).path("total").asLong()).isEqualTo(1);
        assertThat(balance(t)).isEqualTo(100);
        // 超过单次上限直接拒绝
        List<Map<String, Object>> tooMany = new ArrayList<>(essays);
        tooMany.addAll(essays.subList(0, 11));
        assertThat(post("/api/ai/essay/correct", t, Map.of("essays", tooMany)).path("code").asInt()).isEqualTo(400);
    }

    /** AC-10 / AC-11：10 篇作文 2 篇空白，只收 8 篇；修正后连续重试两次，最多再扣一次。 */
    @Test
    void essayBatchPartialAndIdempotentRetry() throws Exception {
        String t = registerVisitor(newPhone());
        List<Map<String, Object>> essays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            essays.add(Map.of("studentName", "学生" + i, "studentId", 1000 + i, "text", i < 8 ? ESSAY + i : "   "));
        }
        JsonNode r = ok(post("/api/ai/essay/correct", t, Map.of("subject", "CHINESE", "fullScore", 60,
                "genre", "记叙文", "title", "我的家乡", "essays", essays)));
        assertThat(r.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(r.path("successItems").asInt()).isEqualTo(8);
        assertThat(r.path("charged").asLong()).isEqualTo(64);
        assertThat(r.path("userMessage").asText()).contains("成功 8").contains("失败 2");
        assertThat(balance(t)).isEqualTo(436);
        String taskId = r.path("taskId").asText();

        // 老师重新上传了第 9 篇的清晰版本
        AiSubtask s9 = subtaskMapper.selectOne(Wrappers.<AiSubtask>lambdaQuery().eq(AiSubtask::getTaskId, taskId)
                .eq(AiSubtask::getItemNo, 9));
        s9.setInputText(ESSAY + "补交");
        subtaskMapper.updateById(s9);
        JsonNode r1 = ok(post("/api/ai/tasks/" + taskId + "/retry", t, null));
        JsonNode r2 = ok(post("/api/ai/tasks/" + taskId + "/retry", t, null));
        assertThat(r1.path("successItems").asInt()).isEqualTo(9);
        assertThat(r2.path("successItems").asInt()).isEqualTo(9);
        assertThat(r2.path("charged").asLong()).isEqualTo(72);
        assertThat(balance(t)).isEqualTo(428);
        // 按学生建档
        JsonNode hist = ok(get("/api/ai/essay/history?studentId=1000", t));
        assertThat(hist.size()).isEqualTo(1);
        assertThat(hist.get(0).path("score").asDouble()).isBetween(0.0, 60.0);
        // 两次重试都关联原任务 ID
        JsonNode flow = ok(get("/api/admin/coin-flow?taskId=" + taskId, adminToken()));
        assertThat(flow.path("ledgers").toString()).contains(taskId + "#9#");
    }

    @Test
    void questionGenerationAndSaveToBank() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode r = ok(post("/api/ai/questions/generate", t, Map.of("subject", "数学", "period", "高中",
                "difficulty", "中等", "count", 3, "requirement", "导数的计算 3 道")));
        assertThat(r.path("charged").asLong()).isEqualTo(6);
        JsonNode qs = om.readTree(r.path("items").get(0).path("output").asText());
        assertThat(qs.size()).isEqualTo(3);
        List<JsonNode> list = new ArrayList<>();
        qs.forEach(list::add);
        JsonNode ids = ok(post("/api/ai/questions/save", t, Map.of("subject", "MATH", "difficulty", "3", "questions", list)));
        assertThat(ids.size()).isEqualTo(3);
        JsonNode mine = ok(get("/api/questions?scope=MINE", t));
        assertThat(mine.path("total").asLong()).isEqualTo(3);
        assertThat(mine.path("rows").get(0).path("qSource").asText()).isEqualTo("AI");
    }

    @Test
    void pptOutlineFreeThenGenerateChargedByPage() throws Exception {
        String t = registerVisitor(newPhone());
        JsonNode o = ok(post("/api/ai/ppt/outline", t, Map.of("subject", "数学", "period", "高中", "topic", "函数的单调性")));
        assertThat(o.path("charged").asLong()).isZero();
        JsonNode outline = om.readTree(o.path("items").get(0).path("output").asText());
        assertThat(balance(t)).isEqualTo(500);
        JsonNode g = ok(post("/api/ai/ppt/generate", t, Map.of("outline", outline, "template", "simple_green")));
        int pages = om.readTree(g.path("items").get(0).path("output").asText()).path("pages").asInt();
        assertThat(pages).isEqualTo(8);
        assertThat(g.path("charged").asLong()).isEqualTo(16);
        String link = ok(get("/api/ai/files/link?taskId=" + g.path("taskId").asText(), t)).asText();
        assertThat(link).startsWith("/api/files/download/");
    }

    @Test
    void knowledgeBaseIsRetrievedInChat() throws Exception {
        String t = registerVisitor(newPhone());
        long kb = ok(post("/api/ai/kb", t, Map.of("name", "我的教案", "shared", false))).path("id").asLong();
        MockMultipartFile f = new MockMultipartFile("file", "单调性.txt", "text/plain",
                "函数单调性的定义：在区间内任取 x1<x2，若 f(x1)<f(x2) 则为增函数。".getBytes(StandardCharsets.UTF_8));
        String s = mvc.perform(multipart("/api/ai/kb/" + kb + "/docs").file(f).header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(om.readTree(s).path("data").path("parseStatus").asText()).isEqualTo("READY");
        JsonNode r = ok(post("/api/ai/chat", t, Map.of("question", "函数单调性的定义是什么", "kbIds", List.of(kb))));
        assertThat(r.path("task").path("items").get(0).path("output").asText()).contains("已参考资料");
        // 别人的知识库不能引用
        String other = registerVisitor(newPhone());
        JsonNode r2 = ok(post("/api/ai/chat", other, Map.of("question", "函数单调性的定义是什么", "kbIds", List.of(kb))));
        assertThat(r2.path("task").path("items").get(0).path("output").asText()).doesNotContain("已参考资料");
    }

    @Test
    void premiumResourceChargesOnceAfterAudit() throws Exception {
        String admin = adminToken();
        MockMultipartFile meta = new MockMultipartFile("meta", "", MediaType.APPLICATION_JSON_VALUE,
                om.writeValueAsBytes(Map.of("scope", "PLATFORM", "title", "函数单调性精品课件", "resType", "COURSEWARE",
                        "level", "PREMIUM", "priceCoins", 20, "period", "1", "subject", "MATH")));
        MockMultipartFile file = new MockMultipartFile("file", "课件.pptx", "application/octet-stream", new byte[]{1, 2, 3});
        String s = mvc.perform(multipart("/api/resources").file(meta).file(file).header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = om.readTree(s).path("data").path("id").asLong();
        ok(post("/api/resources/" + id + "/submit", admin, null));
        String t = registerVisitor(newPhone());
        assertThat(get("/api/resources/" + id, t).path("code").asInt()).isEqualTo(404); // 待审核不可见
        JsonNode pending = ok(get("/api/audit/pending?bizType=RESOURCE", admin)).path("rows");
        long auditId = 0;
        for (JsonNode a : pending) {
            if (a.path("bizId").asLong() == id) {
                auditId = a.path("id").asLong();
            }
        }
        ok(post("/api/audit/" + auditId + "/decide", admin, Map.of("approve", true)));
        assertThat(ok(get("/api/resources/" + id, t)).path("resStatus").asText()).isEqualTo("ONLINE");
        assertThat(ok(post("/api/resources/" + id + "/download", t, null)).path("paidCoins").asLong()).isEqualTo(20);
        assertThat(ok(post("/api/resources/" + id + "/download", t, null)).path("paidCoins").asLong()).isZero();
        assertThat(balance(t)).isEqualTo(480);
    }

    @Test
    void questionImportValidatesAndSmartPaper() throws Exception {
        String t = registerVisitor(newPhone());
        String bad = "【题型】单选题\n【难度】超难\n【题文】1+1=?\n【选项】\nA. 1\n【答案】C";
        JsonNode p = ok(post("/api/questions/import/preview", t, Map.of("text", bad)));
        assertThat(p.path("ok").asBoolean()).isFalse();
        assertThat(p.path("errors").toString()).contains("难度").contains("选项");
        assertThat(post("/api/questions/import", t, Map.of("text", bad)).path("code").asInt()).isEqualTo(3004);
        String good = """
                【题型】单选题
                【难度】较易
                【题文】1+1=?
                【选项】
                A. 1
                B. 2
                【答案】B
                【解析】基本运算
                【题组】阅读下面材料，回答问题。
                【题组小题】
                【题型】填空题
                【题文】材料的主旨是____
                【答案】略
                【题组小题】
                【题型】解答题
                【题文】谈谈你的看法。
                【题组结束】
                """;
        JsonNode ids = ok(post("/api/questions/import", t, Map.of("text", good, "subject", "MATH")));
        assertThat(ids.size()).isEqualTo(3);

        JsonNode paper = ok(post("/api/papers/smart", t, Map.of("subject", "MATH", "period", "1",
                "knowledge", List.of("集合"), "difficulty", "2", "typeCounts", Map.of("SINGLE", 2, "JUDGE", 1))));
        assertThat(paper.path("sections").size()).isEqualTo(2);
        assertThat(paper.path("paper").path("totalScore").asDouble()).isEqualTo(12.0);
        assertThat(paper.path("analysis").path("byKnowledge").path("集合").asInt()).isEqualTo(3);
        long paperId = paper.path("paper").path("id").asLong();
        String link = ok(post("/api/papers/" + paperId + "/export", t, null)).asText();
        assertThat(link).startsWith("/api/files/download/");
    }
}
