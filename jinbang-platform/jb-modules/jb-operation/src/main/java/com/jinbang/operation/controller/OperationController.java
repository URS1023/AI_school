package com.jinbang.operation.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.operation.domain.CmsContent;
import com.jinbang.operation.domain.MsgMessage;
import com.jinbang.operation.domain.OpAudit;
import com.jinbang.operation.domain.OpFeedback;
import com.jinbang.operation.domain.OpReport;
import com.jinbang.operation.service.AuditService;
import com.jinbang.operation.service.ContentSafetyService;
import com.jinbang.operation.service.MessageService;
import com.jinbang.operation.service.ServiceDeskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class OperationController {
    private final MessageService messageService;
    private final AuditService auditService;
    private final ServiceDeskService deskService;
    private final ContentSafetyService safetyService;

    public record IdsReq(List<Long> ids) {
    }

    public record DecideReq(boolean approve, String reason) {
    }

    public record ReplyReq(String reply, boolean close) {
    }

    public record WordReq(String word) {
    }

    // ---------------- 消息中心 ----------------

    @GetMapping("/api/messages")
    public R<PageResult<MsgMessage>> messages(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) Boolean unread, PageQuery q) {
        return R.ok(messageService.list(LoginHelper.userId(), category, unread, q));
    }

    @GetMapping("/api/messages/unread-count")
    public R<Long> unread() {
        return R.ok(messageService.unreadCount(LoginHelper.userId()));
    }

    @PostMapping("/api/messages/read")
    public R<Void> read(@RequestBody IdsReq req) {
        messageService.markRead(LoginHelper.userId(), req.ids());
        return R.ok();
    }

    // ---------------- 审核中心 ----------------

    @SaCheckPermission(value = {"op:audit", "school:resource:audit"}, mode = SaMode.OR)
    @GetMapping("/api/audit/pending")
    public R<PageResult<OpAudit>> pending(@RequestParam(required = false) String bizType, PageQuery q) {
        return R.ok(auditService.pending(bizType, q));
    }

    @SaCheckPermission(value = {"op:audit", "school:resource:audit"}, mode = SaMode.OR)
    @PostMapping("/api/audit/{id}/decide")
    public R<Void> decide(@PathVariable Long id, @RequestBody DecideReq req) {
        auditService.decide(id, req.approve(), req.reason());
        return R.ok();
    }

    @SaCheckPermission("op:audit")
    @PostMapping("/api/admin/sensitive-words")
    public R<Void> addWord(@RequestBody WordReq req) {
        safetyService.addWord(req.word());
        return R.ok();
    }

    // ---------------- 反馈 ----------------

    @PostMapping("/api/feedback")
    public R<OpFeedback> feedback(@RequestBody ServiceDeskService.FeedbackReq req) {
        return R.ok(deskService.submitFeedback(req));
    }

    @GetMapping("/api/feedback/mine")
    public R<PageResult<OpFeedback>> myFeedback(PageQuery q) {
        return R.ok(deskService.myFeedback(q));
    }

    @SaCheckPermission("op:feedback")
    @GetMapping("/api/admin/feedback")
    public R<PageResult<OpFeedback>> feedbackQueue(@RequestParam(required = false) String status, PageQuery q) {
        return R.ok(deskService.feedbackQueue(status, q));
    }

    @SaCheckPermission("op:feedback")
    @PostMapping("/api/admin/feedback/{id}/reply")
    public R<Void> reply(@PathVariable Long id, @RequestBody ReplyReq req) {
        deskService.replyFeedback(id, req.reply(), req.close());
        return R.ok();
    }

    // ---------------- 举报 ----------------

    @PostMapping("/api/reports")
    public R<OpReport> report(@RequestBody ServiceDeskService.ReportReq req) {
        return R.ok(deskService.report(req));
    }

    @GetMapping("/api/reports/mine")
    public R<PageResult<OpReport>> myReports(PageQuery q) {
        return R.ok(deskService.myReports(q));
    }

    @SaCheckPermission("op:audit")
    @GetMapping("/api/admin/reports")
    public R<PageResult<OpReport>> reportQueue(PageQuery q) {
        return R.ok(deskService.reportQueue(q));
    }

    @SaCheckPermission("op:audit")
    @PostMapping("/api/admin/reports/{id}/handle")
    public R<Void> handle(@PathVariable Long id, @RequestBody DecideReq req) {
        deskService.handleReport(id, req.approve(), req.reason());
        return R.ok();
    }

    // ---------------- CMS ----------------

    @GetMapping("/api/open/cms/{type}")
    public R<List<CmsContent>> cms(@PathVariable String type) {
        return R.ok(deskService.published(type.toUpperCase()));
    }

    @SaCheckPermission("op:cms")
    @GetMapping("/api/admin/cms")
    public R<List<CmsContent>> allCms(@RequestParam(required = false) String type) {
        return R.ok(deskService.allCms(type));
    }

    @SaCheckPermission("op:cms")
    @PostMapping("/api/admin/cms")
    public R<CmsContent> saveCms(@RequestBody CmsContent c) {
        return R.ok(deskService.saveCms(c));
    }
}
