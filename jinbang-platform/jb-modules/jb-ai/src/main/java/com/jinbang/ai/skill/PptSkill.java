package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.ai.runtime.AgentSkill;
import com.jinbang.ai.runtime.SkillContext;
import com.jinbang.api.ai.CoursewareGeneratedEvent;
import com.jinbang.common.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PPT 生成三步（PRD 7.4）：① 填要求 → ② 大纲（可编辑，免费）→ ③ 选模板生成课件（按页计费）。
 * 生成后发事件，教学模块把课件放进教师的「独立备课」目录（➕P0 主站与 AI 平台打通）。
 */
@Component
@RequiredArgsConstructor
public class PptSkill implements AgentSkill {
    private final ObjectMapper om;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;

    @Override
    public Set<String> agentCodes() {
        return Set.of("ppt");
    }

    @Override
    public ItemResult handle(SkillContext ctx, JsonNode p, String input) {
        if ("generate".equals(p.path("op").asText())) {
            JsonNode outline = p.path("outline");
            if (!outline.path("chapters").isArray() || outline.path("chapters").isEmpty()) {
                return ItemResult.empty("大纲为空，请先生成或编辑大纲");
            }
            PptBuilder.Built built = PptBuilder.build(outline, p.path("template").asText("urban_blue"));
            String key = storage.put("ppt", "pptx", built.content());
            String title = outline.path("title").asText("AI 课件");
            Long catalogId = p.hasNonNull("catalogId") ? p.get("catalogId").asLong() : null;
            events.publishEvent(new CoursewareGeneratedEvent(ctx.getIdentity().userId(), ctx.getTaskId(), title, "PPTX",
                    key, built.content().length, catalogId));
            return ItemResult.success(om.createObjectNode().put("storageKey", key).put("pages", built.pages())
                    .put("title", title).toString(), built.pages());
        }
        String prompt = String.format("学科：%s；学段：%s；教材版本：%s；课题：%s；其他说明：%s",
                p.path("subject").asText(""), p.path("period").asText(""), p.path("edition").asText(""),
                p.path("topic").asText(input == null ? "" : input), p.path("requirement").asText("无"));
        JsonNode outline = JsonExtract.object(om, ctx.chat(prompt, false).content());
        if (outline == null || !outline.path("chapters").isArray() || outline.path("chapters").isEmpty()) {
            return ItemResult.empty("没有生成有效大纲，请补充课题信息后重试");
        }
        return ItemResult.success(outline.toString(), 1);
    }

    /** 按大纲估算页数：封面 + 每章一页 + 每节一页。 */
    public static int estimatePages(JsonNode outline) {
        int pages = 1;
        for (JsonNode ch : outline.path("chapters")) {
            pages += 1 + ch.path("sections").size();
        }
        return pages;
    }
}
