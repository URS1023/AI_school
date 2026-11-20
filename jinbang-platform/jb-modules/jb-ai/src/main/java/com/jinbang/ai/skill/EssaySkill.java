package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinbang.ai.domain.AiEssayRecord;
import com.jinbang.ai.mapper.AiEssayRecordMapper;
import com.jinbang.ai.runtime.AgentSkill;
import com.jinbang.ai.runtime.SkillContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 作文批改（PRD 7.6）：每篇一个子项；空白或无法识别的不收费（AC-10）；结果按学生建档（➕P0）。
 * 得分强制夹在 [0, 满分]，避免出现超过满分的分数（同 AC-18 的思路）。
 */
@Component
@RequiredArgsConstructor
public class EssaySkill implements AgentSkill {
    private static final int MIN_CHARS = 20;

    private final ObjectMapper om;
    private final OcrService ocr;
    private final AiEssayRecordMapper recordMapper;

    @Override
    public Set<String> agentCodes() {
        return Set.of("essay");
    }

    @Override
    public ItemResult handle(SkillContext ctx, JsonNode p, String input) {
        String text = ocr.textOf(input).trim();
        if (text.replaceAll("\\s", "").length() < MIN_CHARS) {
            return ItemResult.empty("未识别到作文内容（空白或图片无法识别）");
        }
        String subject = p.path("subject").asText("CHINESE");
        int full = p.path("fullScore").asInt(60);
        String prompt = String.format("科目：%s；学段：%s；年级：%s；文体：%s；满分：%d。作文题目及要求：%s\n\n学生作文：\n%s",
                subject, p.path("period").asText("高中"), p.path("grade").asText(""), p.path("genre").asText("写作"),
                full, p.path("title").asText(""), text);
        JsonNode report = JsonExtract.object(om, ctx.chat(prompt, false).content());
        if (report == null || !report.has("score")) {
            throw new IllegalStateException("essay report unparseable");
        }
        BigDecimal score = BigDecimal.valueOf(report.path("score").asDouble())
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(full)).setScale(1, RoundingMode.HALF_UP);
        ((ObjectNode) report).put("score", score);
        ((ObjectNode) report).put("fullScore", full);
        JsonNode student = p.path("students").path(String.valueOf(ctx.getItemNo()));
        AiEssayRecord rec = new AiEssayRecord();
        rec.setUserId(ctx.getIdentity().userId());
        rec.setStudentId(student.hasNonNull("studentId") ? student.get("studentId").asLong() : null);
        rec.setStudentName(student.path("name").asText(null));
        rec.setTaskId(ctx.getTaskId());
        rec.setItemNo(ctx.getItemNo());
        rec.setSubject(subject);
        rec.setGenre(p.path("genre").asText(null));
        rec.setEssayTitle(p.path("title").asText(null));
        rec.setFullScore(full);
        rec.setEssayText(text);
        rec.setScore(score);
        rec.setReport(report.toString());
        recordMapper.insert(rec);
        return ItemResult.success(report.toString(), 1);
    }
}
