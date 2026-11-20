package com.jinbang.operation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.api.operation.ReportUpheldEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.operation.domain.CmsContent;
import com.jinbang.operation.domain.OpFeedback;
import com.jinbang.operation.domain.OpReport;
import com.jinbang.operation.mapper.CmsContentMapper;
import com.jinbang.operation.mapper.OpFeedbackMapper;
import com.jinbang.operation.mapper.OpReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 反馈工单（PRD 7.9、5.2 我的反馈记录）、举报（11.7）、CMS（Banner / 资讯 / 公告，10.2.5）。 */
@Service
@RequiredArgsConstructor
public class ServiceDeskService {
    private final OpFeedbackMapper feedbackMapper;
    private final OpReportMapper reportMapper;
    private final CmsContentMapper cmsMapper;
    private final NotifyApi notifyApi;
    private final ApplicationEventPublisher events;

    // ---------------- 反馈 ----------------

    public record FeedbackReq(String feedbackType, String sourceApp, String contact, String content, String images,
                              String remark) {
    }

    public OpFeedback submitFeedback(FeedbackReq req) {
        BizException.throwIf(req.contact() == null || req.contact().isBlank() || req.content() == null
                || req.content().isBlank(), ErrorCode.BAD_REQUEST, "联系方式和反馈内容必填");
        OpFeedback f = new OpFeedback();
        f.setUserId(LoginHelper.userId());
        f.setFeedbackType(req.feedbackType());
        f.setSourceApp(req.sourceApp());
        f.setContact(req.contact());
        f.setContent(req.content());
        f.setImages(req.images());
        f.setRemark(req.remark());
        f.setFbStatus("OPEN");
        feedbackMapper.insert(f);
        return f;
    }

    public PageResult<OpFeedback> myFeedback(PageQuery q) {
        return PageResult.of(feedbackMapper.selectPage(q.toPage(), Wrappers.<OpFeedback>lambdaQuery()
                .eq(OpFeedback::getUserId, LoginHelper.userId()).orderByDesc(OpFeedback::getCreatedAt)));
    }

    public PageResult<OpFeedback> feedbackQueue(String status, PageQuery q) {
        return PageResult.of(feedbackMapper.selectPage(q.toPage(), Wrappers.<OpFeedback>lambdaQuery()
                .eq(status != null, OpFeedback::getFbStatus, status).orderByAsc(OpFeedback::getCreatedAt)));
    }

    @Transactional
    public void replyFeedback(Long id, String reply, boolean close) {
        OpFeedback f = BizException.notNull(feedbackMapper.selectById(id), "反馈");
        f.setReply(reply);
        f.setRepliedBy(LoginHelper.userId());
        f.setRepliedAt(LocalDateTime.now());
        f.setFbStatus(close ? "CLOSED" : "REPLIED");
        feedbackMapper.updateById(f);
        notifyApi.send(List.of(f.getUserId()), "SYSTEM", "你的反馈有了回复", reply, "feedback:" + id);
    }

    // ---------------- 举报 ----------------

    public record ReportReq(String targetType, Long targetId, Long targetOwnerId, String reason) {
    }

    public OpReport report(ReportReq req) {
        BizException.throwIf(req.reason() == null || req.reason().isBlank(), ErrorCode.BAD_REQUEST, "请填写举报原因");
        Long me = LoginHelper.userId();
        boolean dup = reportMapper.exists(Wrappers.<OpReport>lambdaQuery().eq(OpReport::getReporterId, me)
                .eq(OpReport::getTargetType, req.targetType()).eq(OpReport::getTargetId, req.targetId())
                .eq(OpReport::getReportStatus, "PENDING"));
        BizException.throwIf(dup, ErrorCode.CONFLICT, "你已举报过该内容，请等待处理");
        OpReport r = new OpReport();
        r.setReporterId(me);
        r.setTargetType(req.targetType());
        r.setTargetId(req.targetId());
        r.setTargetOwnerId(req.targetOwnerId());
        r.setReason(req.reason());
        r.setReportStatus("PENDING");
        reportMapper.insert(r);
        return r;
    }

    public PageResult<OpReport> myReports(PageQuery q) {
        return PageResult.of(reportMapper.selectPage(q.toPage(), Wrappers.<OpReport>lambdaQuery()
                .eq(OpReport::getReporterId, LoginHelper.userId()).orderByDesc(OpReport::getCreatedAt)));
    }

    public PageResult<OpReport> reportQueue(PageQuery q) {
        return PageResult.of(reportMapper.selectPage(q.toPage(), Wrappers.<OpReport>lambdaQuery()
                .eq(OpReport::getReportStatus, "PENDING").orderByAsc(OpReport::getCreatedAt)));
    }

    /** 成立：下架内容（事件通知所属模块）+ 通知双方；不成立：通知举报人。 */
    @Transactional
    @OperLog(module = "运营-举报", action = "处理举报")
    public void handleReport(Long id, boolean upheld, String result) {
        OpReport r = BizException.notNull(reportMapper.selectById(id), "举报");
        BizException.throwIf(!"PENDING".equals(r.getReportStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该举报已处理");
        r.setReportStatus(upheld ? "UPHELD" : "REJECTED");
        r.setResult(result);
        r.setHandledBy(LoginHelper.userId());
        r.setHandledAt(LocalDateTime.now());
        reportMapper.updateById(r);
        notifyApi.send(List.of(r.getReporterId()), "AUDIT", "举报处理结果",
                upheld ? "你举报的内容已核实并处理，感谢你的反馈" : "经核实，你举报的内容未违反平台规则：" + result,
                "report:" + id);
        if (upheld) {
            events.publishEvent(new ReportUpheldEvent(r.getId(), r.getTargetType(), r.getTargetId(), result));
            if (r.getTargetOwnerId() != null) {
                notifyApi.send(List.of(r.getTargetOwnerId()), "AUDIT", "你发布的内容已被下架",
                        "原因：" + result, "report:" + id);
            }
        }
    }

    // ---------------- CMS ----------------

    public List<CmsContent> published(String type) {
        return cmsMapper.selectList(Wrappers.<CmsContent>lambdaQuery().eq(CmsContent::getContentType, type)
                .eq(CmsContent::getContentStatus, "PUBLISHED").le(CmsContent::getPublishAt, LocalDateTime.now())
                .orderByAsc(CmsContent::getSortNo).orderByDesc(CmsContent::getPublishAt));
    }

    public CmsContent saveCms(CmsContent c) {
        if (c.getContentStatus() == null) {
            c.setContentStatus("DRAFT");
        }
        if (c.getViewCount() == null) {
            c.setViewCount(0L);
        }
        if (c.getSortNo() == null) {
            c.setSortNo(0);
        }
        if ("PUBLISHED".equals(c.getContentStatus()) && c.getPublishAt() == null) {
            c.setPublishAt(LocalDateTime.now());
        }
        if (c.getId() == null) {
            cmsMapper.insert(c);
        } else {
            cmsMapper.updateById(c);
        }
        return c;
    }

    public List<CmsContent> allCms(String type) {
        return new ArrayList<>(cmsMapper.selectList(Wrappers.<CmsContent>lambdaQuery()
                .eq(type != null, CmsContent::getContentType, type).orderByDesc(CmsContent::getCreatedAt)));
    }
}
