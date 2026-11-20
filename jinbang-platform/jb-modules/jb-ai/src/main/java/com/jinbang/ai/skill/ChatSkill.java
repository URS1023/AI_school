package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.ai.kb.KbService;
import com.jinbang.ai.model.ModelProvider;
import com.jinbang.ai.runtime.AgentSkill;
import com.jinbang.ai.runtime.SkillContext;
import com.jinbang.api.ai.AiToolProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对话类智能体：智能问答、客服、学伴、家长助手、家校沟通、教案生成、学情分析助手。
 * 意图路由：匹配到的工具（题库 / 资源 / 学情）先执行，结果作为【参考资料】交给模型；也可以引用知识库。
 */
@Component
@RequiredArgsConstructor
public class ChatSkill implements AgentSkill {
    private final List<AiToolProvider> tools;
    private final KbService kbService;

    @Override
    public Set<String> agentCodes() {
        return Set.of("ask", "service", "study_buddy", "parent_helper", "home_school", "lesson_plan", "learning");
    }

    @Override
    public ItemResult handle(SkillContext ctx, JsonNode params, String question) {
        if (question == null || question.isBlank()) {
            return ItemResult.empty("请输入问题");
        }
        List<String> refs = new ArrayList<>();
        for (AiToolProvider tool : tools) {
            if (tool.matches(ctx.getAgent().getAgentCode(), question)) {
                // 工具异常直接抛出：运行时按「系统失败」处理，不扣费、不暴露内部信息
                String r = tool.run(ctx.getIdentity(), question);
                if (r != null && !r.isBlank()) {
                    refs.add("[" + tool.name() + "] " + r);
                }
            }
        }
        if (params != null && params.has("kbIds")) {
            List<Long> kbIds = new ArrayList<>();
            params.get("kbIds").forEach(n -> kbIds.add(n.asLong()));
            kbService.retrieve(ctx.getIdentity().userId(), kbIds, question, 3)
                    .forEach(c -> refs.add("[知识库:" + c.fileName() + "] " + c.content()));
        }
        if ("learning".equals(ctx.getAgent().getAgentCode()) && refs.isEmpty()) {
            return ItemResult.empty("当前身份范围内没有可分析的考试或作业数据");
        }
        StringBuilder content = new StringBuilder();
        if (!refs.isEmpty()) {
            content.append("【参考资料】\n");
            for (int i = 0; i < refs.size(); i++) {
                content.append(i + 1).append(". ").append(refs.get(i)).append('\n');
            }
            content.append("\n【问题】\n");
        }
        content.append(question);
        ModelProvider.ChatResult r = ctx.chat(content.toString(), true);
        String out = r.content() == null ? "" : r.content().trim();
        int units = ctx.getAgent().getPriceMode().equals("PER_KCHAR") ? (out.length() + 999) / 1000 : 1;
        if (r.stopped()) {
            double expected = Math.max(1, ctx.getAgent().getDefaultUnits()) * 1000.0;
            return ItemResult.stopped(out, units, Math.min(1.0, out.length() / Math.min(expected, 2000.0)));
        }
        if (out.isEmpty()) {
            return ItemResult.empty("没有生成有效内容，建议换个问法或补充更具体的要求");
        }
        return ItemResult.success(out, Math.max(1, units));
    }
}
