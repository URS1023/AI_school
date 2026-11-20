package com.jinbang.teaching.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.api.account.AccountApi;
import com.jinbang.api.ai.AiApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.teaching.domain.TcMaterial;
import com.jinbang.teaching.domain.TcSmartPrep;
import com.jinbang.teaching.mapper.TcSmartPrepMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 智能备课（PRD 4.1.2、11.2）：输入主题 → AI 大纲（⚠️P0 修复：大纲可编辑）→ 选模板 → 生成 PPT 存入目录
 * → 成长奖励 +20 经验 +10 金币（每次完成一次，按智能备课 ID 幂等）。另提供 AI 教案（Word，➕P0）。
 */
@Service
@RequiredArgsConstructor
public class SmartPrepService {
    private final TcSmartPrepMapper prepMapper;
    private final PrepService prepService;
    private final AiApi aiApi;
    private final AccountApi accountApi;
    private final FileStorage storage;
    private final ObjectMapper om;

    public record StartReq(Long catalogId, String topic, String edition, String course, String requirement,
                           String subject, String period) {
    }

    public record PrepView(TcSmartPrep prep, JsonNode outline, TcMaterial material) {
    }

    @Transactional
    public PrepView start(CurrentIdentity ci, StartReq req) {
        BizException.throwIf(req.topic() == null || req.topic().isBlank(), ErrorCode.BAD_REQUEST, "请输入备课主题");
        Map<String, Object> p = new HashMap<>();
        p.put("op", "outline");
        p.put("topic", req.topic());
        p.put("edition", req.edition() == null ? "" : req.edition());
        p.put("subject", req.subject() == null ? "" : req.subject());
        p.put("period", req.period() == null ? "" : req.period());
        p.put("requirement", req.requirement() == null ? "无" : req.requirement());
        AiApi.AiResult r = aiApi.runFree(ci.userId(), "ppt", req.topic(), p);
        TcSmartPrep sp = new TcSmartPrep();
        sp.setOwnerUserId(ci.userId());
        sp.setCatalogId(req.catalogId());
        sp.setTopic(req.topic());
        sp.setEdition(req.edition());
        sp.setCourse(req.course());
        sp.setRequirement(req.requirement());
        sp.setOutlineJson(r.content());
        sp.setPrepStatus("OUTLINE");
        sp.setRewarded(false);
        prepMapper.insert(sp);
        return view(sp, null);
    }

    /** 编辑大纲：增删章节、改写（P0）。 */
    public PrepView editOutline(CurrentIdentity ci, Long id, JsonNode outline) {
        TcSmartPrep sp = own(ci, id);
        BizException.throwIf(!outline.path("chapters").isArray() || outline.path("chapters").isEmpty(), ErrorCode.BAD_REQUEST,
                "大纲至少要有一章");
        sp.setOutlineJson(outline.toString());
        prepMapper.updateById(sp);
        return view(sp, null);
    }

    /** 选模板生成 PPT：课件存入当前目录，发放成长奖励。 */
    @Transactional
    public PrepView generate(CurrentIdentity ci, Long id, String template) {
        TcSmartPrep sp = own(ci, id);
        JsonNode outline = read(sp.getOutlineJson());
        if (sp.getCourse() != null && !sp.getCourse().isBlank()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) outline).put("title", sp.getCourse());
        }
        Map<String, Object> p = new HashMap<>();
        p.put("op", "generate");
        p.put("outline", outline);
        p.put("template", template == null ? "urban_blue" : template);
        if (sp.getCatalogId() != null) {
            p.put("catalogId", sp.getCatalogId());
        }
        AiApi.AiResult r = aiApi.runFree(ci.userId(), "ppt", sp.getTopic(), p);
        TcMaterial m = prepService.findBySourceRef(ci.userId(), r.taskId());
        sp.setTemplateCode(template);
        sp.setPrepStatus("GENERATED");
        sp.setMaterialId(m == null ? null : m.getId());
        if (!Boolean.TRUE.equals(sp.getRewarded())) {
            accountApi.addExp(ci.userId(), 20, "SMART_PREP", String.valueOf(sp.getId()), "完成智能备课");
            accountApi.grantCoins(ci.userId(), 10, "SMART_PREP", String.valueOf(sp.getId()), "完成智能备课");
            sp.setRewarded(true);
        }
        prepMapper.updateById(sp);
        return view(sp, m);
    }

    /** AI 生成教案（Word），按「教案 / 学案生成」智能体计费，存入目录。 */
    @Transactional
    public TcMaterial lessonPlan(CurrentIdentity ci, Long catalogId, String topic, String requirement) {
        BizException.throwIf(topic == null || topic.isBlank(), ErrorCode.BAD_REQUEST, "请输入课题");
        AiApi.AiResult r = aiApi.run(ci.userId(), "lesson_plan", "课题：" + topic + "。要求：" + (requirement == null ? "无" : requirement));
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun title = doc.createParagraph().createRun();
            title.setText(topic + " 教案");
            title.setBold(true);
            title.setFontSize(18);
            for (String line : r.content().split("\\R")) {
                doc.createParagraph().createRun().setText(line);
            }
            XWPFRun tip = doc.createParagraph().createRun();
            tip.setText("（内容由 AI 生成，仅供参考）");
            tip.setItalic(true);
            doc.write(out);
            docx = out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Long target = catalogId == null ? prepService.aiFolder(ci.userId()).getId() : catalogId;
        return prepService.add(ci.userId(), target, topic + " 教案.docx", "DOCX", storage.put("prep", "docx", docx),
                docx.length, "AI_PLAN", r.taskId());
    }

    private PrepView view(TcSmartPrep sp, TcMaterial m) {
        return new PrepView(sp, read(sp.getOutlineJson()), m);
    }

    private TcSmartPrep own(CurrentIdentity ci, Long id) {
        TcSmartPrep sp = prepMapper.selectById(id);
        if (sp == null || !sp.getOwnerUserId().equals(ci.userId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "备课记录不存在");
        }
        return sp;
    }

    private JsonNode read(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "大纲格式错误");
        }
    }
}
