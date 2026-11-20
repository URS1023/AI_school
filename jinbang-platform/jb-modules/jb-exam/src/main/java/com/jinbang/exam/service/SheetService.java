package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinbang.api.resource.QuestionApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.exam.domain.ExSheet;
import com.jinbang.exam.domain.ExSheetVersion;
import com.jinbang.exam.mapper.ExSheetMapper;
import com.jinbang.exam.mapper.ExSheetVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 答题卡与版本（PRD 6.3「版本与锁定规则」、AC-04）：
 * 下载 / 打印即冻结当前版本；冻结后再改 → 自动生成新版本；有扫描记录的版本永久锁定；
 * 二维码带「答题卡 ID + 版本号」，旧版纸卷扫进来按旧版本识别和判分。
 */
@Service
@RequiredArgsConstructor
public class SheetService {
    private final ExSheetMapper sheetMapper;
    private final ExSheetVersionMapper versionMapper;
    private final QuestionApi questionApi;
    private final ObjectMapper om;

    public record Block(String no, String type, boolean objective, BigDecimal fullScore, String answer, Integer options,
                        Long questionId, Integer minWords) {
    }

    public record SheetReq(String name, String subject, Long paperId, String paperSize, String sheetStyle,
                           String markMode, Integer studentNoDigits, String noStyle, List<Block> blocks) {
    }

    public record SheetView(ExSheet sheet, ExSheetVersion version, List<Block> blocks) {
    }

    public record Download(Long sheetId, int versionNo, String qrPayload, List<Block> blocks, BigDecimal totalScore,
                           String printRule) {
    }

    /** 新建答题卡：可以由试卷一键生成（题目、分值、标准答案都带过来），也可以手工配置题目块。 */
    @Transactional
    public SheetView create(CurrentIdentity ci, SheetReq req) {
        List<Block> blocks = req.blocks();
        String subject = req.subject();
        if (req.paperId() != null) {
            QuestionApi.PaperDTO paper = questionApi.getPaper(req.paperId());
            blocks = paper.items().stream().map(i -> new Block(i.no(), i.type(), i.objective(), i.fullScore(), i.answer(),
                    i.objective() ? 4 : null, i.questionId(), null)).toList();
            subject = subject == null ? paper.subject() : subject;
        }
        validate(blocks);
        ExSheet s = new ExSheet();
        s.setOwnerUserId(ci.userId());
        s.setSheetName(req.name() == null || req.name().isBlank() ? "答题卡" : req.name());
        s.setSubject(subject);
        s.setPaperId(req.paperId());
        s.setPaperSize(req.paperSize() == null ? "A4" : req.paperSize());
        s.setSheetStyle(req.sheetStyle() == null ? "CARD_ONLY" : req.sheetStyle());
        s.setMarkMode(req.markMode() == null ? "SCAN_THEN_MARK" : req.markMode());
        s.setStudentNoDigits(req.studentNoDigits() == null ? 6 : req.studentNoDigits());
        s.setNoStyle(req.noStyle() == null ? "HANDWRITE" : req.noStyle());
        s.setCurrentVersion(1);
        sheetMapper.insert(s);
        ExSheetVersion v = newVersion(s, 1, blocks);
        return new SheetView(s, v, blocks);
    }

    /** 修改题目块：当前版本已冻结或已锁定 → 生成新版本；否则原地修改。 */
    @Transactional
    public SheetView updateBlocks(CurrentIdentity ci, Long sheetId, List<Block> blocks) {
        ExSheet s = own(ci, sheetId);
        validate(blocks);
        ExSheetVersion cur = current(s);
        if (Boolean.TRUE.equals(cur.getFrozen()) || Boolean.TRUE.equals(cur.getLocked())) {
            int next = s.getCurrentVersion() + 1;
            ExSheetVersion v = newVersion(s, next, blocks);
            s.setCurrentVersion(next);
            sheetMapper.updateById(s);
            return new SheetView(s, v, blocks);
        }
        cur.setLayoutJson(toJson(blocks));
        cur.setTotalScore(total(blocks));
        versionMapper.updateById(cur);
        return new SheetView(s, cur, blocks);
    }

    /** 下载 / 打印：冻结当前版本快照。一张 A4 只印一份答题卡（避免正反两个二维码）。 */
    @Transactional
    public Download download(CurrentIdentity ci, Long sheetId) {
        ExSheet s = own(ci, sheetId);
        ExSheetVersion v = current(s);
        if (!Boolean.TRUE.equals(v.getFrozen())) {
            v.setFrozen(true);
            versionMapper.updateById(v);
        }
        return new Download(s.getId(), v.getVersionNo(), "JBSHEET:" + s.getId() + ":" + v.getVersionNo(), blocks(v),
                v.getTotalScore(), "一张 A4 纸只打印一份答题卡，请勿双面打印两份");
    }

    public SheetView view(CurrentIdentity ci, Long sheetId) {
        ExSheet s = BizException.notNull(sheetMapper.selectById(sheetId), "答题卡");
        ExSheetVersion v = current(s);
        return new SheetView(s, v, blocks(v));
    }

    public List<ExSheet> list(CurrentIdentity ci) {
        return sheetMapper.selectList(Wrappers.<ExSheet>lambdaQuery().eq(ExSheet::getOwnerUserId, ci.userId())
                .orderByDesc(ExSheet::getCreatedAt));
    }

    public List<ExSheetVersion> versions(Long sheetId) {
        return versionMapper.selectList(Wrappers.<ExSheetVersion>lambdaQuery().eq(ExSheetVersion::getSheetId, sheetId)
                .orderByAsc(ExSheetVersion::getVersionNo));
    }

    /** 作业 / 考试关联答题卡时，绑定的是「当前版本」的具体版本 ID。 */
    public ExSheetVersion currentVersion(Long sheetId) {
        return current(BizException.notNull(sheetMapper.selectById(sheetId), "答题卡"));
    }

    public ExSheetVersion version(Long versionId) {
        return BizException.notNull(versionMapper.selectById(versionId), "答题卡版本");
    }

    /** 按二维码解析版本（扫描时用）。 */
    public ExSheetVersion versionByQr(String qr) {
        if (qr == null || !qr.startsWith("JBSHEET:")) {
            return null;
        }
        String[] p = qr.split(":");
        if (p.length != 3) {
            return null;
        }
        return versionMapper.selectOne(Wrappers.<ExSheetVersion>lambdaQuery()
                .eq(ExSheetVersion::getSheetId, Long.valueOf(p[1])).eq(ExSheetVersion::getVersionNo, Integer.valueOf(p[2])));
    }

    /** 有扫描记录即锁定。 */
    public void lock(ExSheetVersion v) {
        if (!Boolean.TRUE.equals(v.getLocked())) {
            v.setLocked(true);
            v.setFrozen(true);
            versionMapper.updateById(v);
        }
    }

    public List<Block> blocks(ExSheetVersion v) {
        try {
            List<Block> out = new ArrayList<>();
            for (JsonNode n : om.readTree(v.getLayoutJson()).path("blocks")) {
                out.add(om.treeToValue(n, Block.class));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("答题卡版面数据损坏", e);
        }
    }

    private ExSheetVersion newVersion(ExSheet s, int no, List<Block> blocks) {
        ExSheetVersion v = new ExSheetVersion();
        v.setSheetId(s.getId());
        v.setVersionNo(no);
        v.setLayoutJson(toJson(blocks));
        v.setTotalScore(total(blocks));
        v.setFrozen(false);
        v.setLocked(false);
        versionMapper.insert(v);
        return v;
    }

    private ExSheetVersion current(ExSheet s) {
        return BizException.notNull(versionMapper.selectOne(Wrappers.<ExSheetVersion>lambdaQuery()
                .eq(ExSheetVersion::getSheetId, s.getId()).eq(ExSheetVersion::getVersionNo, s.getCurrentVersion())), "答题卡版本");
    }

    private ExSheet own(CurrentIdentity ci, Long id) {
        ExSheet s = BizException.notNull(sheetMapper.selectById(id), "答题卡");
        if (!s.getOwnerUserId().equals(ci.userId()) && !ci.hasPerm("exam:admin")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return s;
    }

    private void validate(List<Block> blocks) {
        BizException.throwIf(blocks == null || blocks.isEmpty(), ErrorCode.BAD_REQUEST, "答题卡至少需要一道题");
        Set<String> nos = new HashSet<>();
        for (Block b : blocks) {
            BizException.throwIf(b.no() == null || !nos.add(b.no()), ErrorCode.BAD_REQUEST, "题号重复或为空：" + b.no());
            BizException.throwIf(b.fullScore() == null || b.fullScore().signum() <= 0, ErrorCode.BAD_REQUEST,
                    "第 " + b.no() + " 题分值必须大于 0");
        }
    }

    private BigDecimal total(List<Block> blocks) {
        return blocks.stream().map(Block::fullScore).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String toJson(List<Block> blocks) {
        ObjectNode root = om.createObjectNode();
        ArrayNode arr = root.putArray("blocks");
        blocks.forEach(b -> arr.add(om.valueToTree(b)));
        return root.toString();
    }
}
