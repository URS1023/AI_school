package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinbang.ai.runtime.AgentSkill;
import com.jinbang.ai.runtime.SkillContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 试卷批改（PRD 7.5）：每页一个子项；可上传标准答案 / 评分细则（➕P0）；
 * 标注方式：对错 / 得分 / 对错及得分；评语可选。每题得分夹在 [0, 该题满分]。
 */
@Component
@RequiredArgsConstructor
public class PaperCorrectSkill implements AgentSkill {
    private final ObjectMapper om;
    private final OcrService ocr;

    @Override
    public Set<String> agentCodes() {
        return Set.of("paper_correct");
    }

    @Override
    public ItemResult handle(SkillContext ctx, JsonNode p, String input) {
        String text = ocr.textOf(input).trim();
        if (text.isEmpty()) {
            return ItemResult.empty("该页未识别到作答内容（空白或图片不清晰）");
        }
        String prompt = String.format("学科：%s；学段年级：%s %s；标注方式：%s；%s。\n标准答案与评分细则：\n%s\n\n第 %d 页作答识别文本：\n%s",
                p.path("subject").asText("数学"), p.path("period").asText("高中"), p.path("grade").asText(""),
                p.path("markMode").asText("标注对错及得分"), p.path("withComment").asBoolean(true) ? "需要评语" : "不要评语",
                p.path("standardAnswer").asText("（未提供，请按学科常识判断）"), ctx.getItemNo(), text);
        JsonNode r = JsonExtract.object(om, ctx.chat(prompt, false).content());
        if (r == null || !r.path("items").isArray()) {
            throw new IllegalStateException("correction result unparseable");
        }
        double total = 0;
        for (JsonNode item : (ArrayNode) r.get("items")) {
            double full = item.path("fullScore").asDouble(0);
            double score = Math.max(0, item.path("score").asDouble(0));
            if (full > 0) {
                score = Math.min(score, full);
            }
            ((ObjectNode) item).put("score", score);
            total += score;
        }
        ((ObjectNode) r).put("total", total);
        if (!p.path("withComment").asBoolean(true)) {
            r.get("items").forEach(i -> ((ObjectNode) i).remove("comment"));
        }
        return ItemResult.success(r.toString(), 1);
    }
}
