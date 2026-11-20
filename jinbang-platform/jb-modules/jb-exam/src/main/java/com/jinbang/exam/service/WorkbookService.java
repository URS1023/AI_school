package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.exam.domain.ExWorkbookPage;
import com.jinbang.exam.mapper.ExWorkbookPageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 教辅页面模板库（PRD 6.2 ➕P0，扫描阅卷能运转的前提）：扫描件类型选「教辅」时，按页面二维码找到这一页的题目与答案。
 * 题号统一加页码前缀（如 12-3），同一次作业跨多页也不会重号。
 */
@Service
@RequiredArgsConstructor
public class WorkbookService {
    public static final String QR_PREFIX = "JBBOOK:";

    private final ExWorkbookPageMapper pageMapper;
    private final ObjectMapper om;

    public record PageReq(String bookCode, String bookName, String subject, Integer pageNo, List<SheetService.Block> blocks) {
    }

    public ExWorkbookPage save(PageReq req) {
        BizException.throwIf(req.bookCode() == null || req.pageNo() == null || req.blocks() == null || req.blocks().isEmpty(),
                ErrorCode.BAD_REQUEST, "请填写教辅编码、页码和题目区域");
        req.blocks().forEach(b -> BizException.throwIf(b.fullScore() == null || b.fullScore().signum() <= 0,
                ErrorCode.BAD_REQUEST, "第 " + b.no() + " 题分值必须大于 0"));
        ExWorkbookPage p = pageMapper.selectOne(Wrappers.<ExWorkbookPage>lambdaQuery().eq(ExWorkbookPage::getBookCode, req.bookCode())
                .eq(ExWorkbookPage::getPageNo, req.pageNo()));
        if (p == null) {
            p = new ExWorkbookPage();
        }
        p.setBookCode(req.bookCode());
        p.setBookName(req.bookName() == null ? req.bookCode() : req.bookName());
        p.setSubject(req.subject());
        p.setPageNo(req.pageNo());
        p.setLayoutJson(om.createObjectNode().set("blocks", om.valueToTree(req.blocks())).toString());
        try {
            if (p.getId() == null) {
                pageMapper.insert(p);
            } else {
                pageMapper.updateById(p);
            }
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "该页已存在");
        }
        return p;
    }

    public List<ExWorkbookPage> pages(String bookCode) {
        return pageMapper.selectList(Wrappers.<ExWorkbookPage>lambdaQuery().eq(ExWorkbookPage::getBookCode, bookCode)
                .orderByAsc(ExWorkbookPage::getPageNo));
    }

    /** 按二维码取页面题目；不是教辅二维码或模板缺失返回 null。 */
    public List<SheetService.Block> blocksByQr(String qr) {
        if (qr == null || !qr.startsWith(QR_PREFIX)) {
            return null;
        }
        String[] p = qr.split(":");
        if (p.length != 3) {
            return null;
        }
        ExWorkbookPage page = pageMapper.selectOne(Wrappers.<ExWorkbookPage>lambdaQuery().eq(ExWorkbookPage::getBookCode, p[1])
                .eq(ExWorkbookPage::getPageNo, Integer.valueOf(p[2])));
        if (page == null) {
            return null;
        }
        try {
            List<SheetService.Block> out = new ArrayList<>();
            for (JsonNode n : om.readTree(page.getLayoutJson()).path("blocks")) {
                SheetService.Block b = om.treeToValue(n, SheetService.Block.class);
                out.add(new SheetService.Block(page.getPageNo() + "-" + b.no(), b.type(), b.objective(), b.fullScore(),
                        b.answer(), b.options(), b.questionId(), b.minWords()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("教辅页面模板数据损坏", e);
        }
    }
}
