package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jinbang.ai.runtime.AgentSkill;
import com.jinbang.ai.runtime.SkillContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 智能生成题目（PRD 7.3）：结构化参数（学段、年级、学科、难度、题型、题量）+ 自然语言要求；按实际生成题数计费。 */
@Component
@RequiredArgsConstructor
public class QuestionSkill implements AgentSkill {
    private final ObjectMapper om;

    @Override
    public Set<String> agentCodes() {
        return Set.of("question");
    }

    @Override
    public ItemResult handle(SkillContext ctx, JsonNode p, String requirement) {
        int count = Math.max(1, Math.min(p.path("count").asInt(5), ctx.getAgent().getMaxUnits()));
        String prompt = String.format("请生成 %d 道题。学段：%s；年级：%s；学科：%s；难度：%s；题型：%s。补充要求：%s",
                count, p.path("period").asText("高中"), p.path("grade").asText(""), p.path("subject").asText("数学"),
                p.path("difficulty").asText("中等"), p.path("type").asText("不限"),
                requirement == null ? "无" : requirement);
        String out = ctx.chat(prompt, false).content();
        JsonNode arr = JsonExtract.array(om, out);
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return ItemResult.empty("没有生成符合格式的题目，建议调整要求后重试");
        }
        ArrayNode valid = om.createArrayNode();
        for (JsonNode q : arr) {
            if (q.hasNonNull("stem") && !q.path("stem").asText().isBlank() && valid.size() < count) {
                valid.add(q);
            }
        }
        if (valid.isEmpty()) {
            return ItemResult.empty("没有生成有效题目");
        }
        return ItemResult.success(valid.toString(), valid.size());
    }
}
