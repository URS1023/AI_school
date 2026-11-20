package com.jinbang.account.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jinbang.account.domain.CoinAdjust;
import com.jinbang.account.domain.ExpLedger;
import com.jinbang.account.domain.PayOrder;
import com.jinbang.account.service.AdjustService;
import com.jinbang.account.service.GrowthService;
import com.jinbang.account.service.LevelRule;
import com.jinbang.account.service.OrderService;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.idempotent.RepeatSubmit;
import com.jinbang.common.security.LoginHelper;
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
public class AccountController {
    private final GrowthService growthService;
    private final OrderService orderService;
    private final AdjustService adjustService;

    public record OrderReq(String packageCode) {
    }

    public record NotifyReq(String orderNo, String channelTxn, String sign) {
    }

    public record AdjustReq(Long userId, long amount, String reason, String refTaskId) {
    }

    public record ApproveReq(boolean pass) {
    }

    @GetMapping("/api/account/me")
    public R<GrowthService.Summary> me() {
        return R.ok(growthService.summary(LoginHelper.userId()));
    }

    /** 金币明细（PRD 2.4 ➕P0）。direction：IN / OUT / FREEZE / UNFREEZE。 */
    @GetMapping("/api/account/coins/ledger")
    public R<PageResult<GrowthService.LedgerVO>> coinLedger(@RequestParam(required = false) String direction, PageQuery q) {
        return R.ok(growthService.coinLedger(LoginHelper.userId(), direction, q));
    }

    @GetMapping("/api/account/exp/ledger")
    public R<PageResult<ExpLedger>> expLedger(PageQuery q) {
        return R.ok(growthService.expLedger(LoginHelper.userId(), q));
    }

    @GetMapping("/api/open/account/levels")
    public R<List<LevelRule.Level>> levels() {
        return R.ok(LevelRule.LEVELS);
    }

    @PostMapping("/api/account/sign-in")
    public R<GrowthService.Summary> signIn() {
        return R.ok(growthService.signIn(LoginHelper.userId()));
    }

    @GetMapping("/api/open/pay/packages")
    public R<List<OrderService.Package>> packages() {
        return R.ok(OrderService.PACKAGES);
    }

    @RepeatSubmit
    @PostMapping("/api/account/orders")
    public R<OrderService.CreateResult> createOrder(@RequestBody OrderReq req) {
        return R.ok(orderService.create(LoginHelper.userId(), req.packageCode()));
    }

    @PostMapping("/api/account/orders/{orderNo}/repay")
    public R<OrderService.CreateResult> repay(@PathVariable String orderNo) {
        return R.ok(orderService.repay(LoginHelper.userId(), orderNo));
    }

    @PostMapping("/api/account/orders/{orderNo}/cancel")
    public R<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(LoginHelper.userId(), orderNo);
        return R.ok();
    }

    @GetMapping("/api/account/orders")
    public R<PageResult<PayOrder>> orders(@RequestParam(required = false) String status, PageQuery q) {
        return R.ok(orderService.myOrders(LoginHelper.userId(), status, q));
    }

    /** 支付通道回调（免登录，靠签名校验）。 */
    @PostMapping("/api/open/pay/notify")
    public R<Void> notifyPaid(@RequestBody NotifyReq req) {
        orderService.onPaid(req.orderNo(), req.channelTxn(), req.sign());
        return R.ok();
    }

    // ---------------- 运营 / 财务 ----------------

    @SaCheckPermission("op:finance")
    @GetMapping("/api/admin/coin-flow")
    public R<AdjustService.TaskFlow> coinFlow(@RequestParam String taskId) {
        return R.ok(adjustService.flowOfTask(taskId));
    }

    @SaCheckPermission("op:finance")
    @PostMapping("/api/admin/coin-adjust")
    public R<CoinAdjust> adjust(@RequestBody AdjustReq req) {
        return R.ok(adjustService.request(req.userId(), req.amount(), req.reason(), req.refTaskId()));
    }

    @SaCheckPermission("op:finance")
    @GetMapping("/api/admin/coin-adjust/pending")
    public R<List<CoinAdjust>> pending() {
        return R.ok(adjustService.pending());
    }

    @SaCheckPermission("op:finance")
    @PostMapping("/api/admin/coin-adjust/{id}/approve")
    public R<Void> approve(@PathVariable Long id, @RequestBody ApproveReq req) {
        adjustService.approve(id, req.pass());
        return R.ok();
    }
}
