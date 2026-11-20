package com.jinbang.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.account.domain.PayOrder;
import com.jinbang.account.mapper.PayOrderMapper;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.statemachine.StateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 充值订单（PRD 2.4、11.6）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    public enum Status { PENDING, PAID, CANCELLED, REFUNDING, REFUNDED }

    public enum Event { PAY, CANCEL, TIMEOUT, APPLY_REFUND, REFUND_DONE, REFUND_REJECT }

    static final StateMachine<Status, Event> FSM = StateMachine.<Status, Event>builder("订单", Status.class)
            .on(Status.PENDING, Event.PAY, Status.PAID)
            .on(Status.PENDING, Event.CANCEL, Status.CANCELLED)
            .on(Status.PENDING, Event.TIMEOUT, Status.CANCELLED)
            .on(Status.PAID, Event.APPLY_REFUND, Status.REFUNDING)
            .on(Status.REFUNDING, Event.REFUND_DONE, Status.REFUNDED)
            .on(Status.REFUNDING, Event.REFUND_REJECT, Status.PAID)
            .build();

    /** 充值套餐（价格待运营确认；1 元 = 10 币，大额有赠送）。 */
    public record Package(String code, long amountFen, long coins, String label) {
    }

    public static final List<Package> PACKAGES = List.of(
            new Package("P6", 600, 60, "60 金榜币"),
            new Package("P30", 3000, 330, "300 + 30 赠送"),
            new Package("P98", 9800, 1150, "980 + 170 赠送"));

    private final PayOrderMapper orderMapper;
    private final AccountService accountService;
    private final org.springframework.beans.factory.ObjectProvider<PaymentGateway> gateways;
    private final NotifyApi notifyApi;

    public record CreateResult(String orderNo, String payParams, LocalDateTime expireAt) {
    }

    @Transactional
    public CreateResult create(Long userId, String packageCode) {
        Package p = PACKAGES.stream().filter(x -> x.code().equals(packageCode)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "充值套餐不存在"));
        PayOrder o = new PayOrder();
        o.setOrderNo(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)));
        o.setUserId(userId);
        o.setOrderType("RECHARGE");
        o.setSubject("金榜币充值 " + p.label());
        o.setCoins(p.coins());
        o.setAmountFen(p.amountFen());
        o.setOrderStatus(Status.PENDING.name());
        o.setPayChannel(gateway().channel());
        o.setExpireAt(LocalDateTime.now().plusMinutes(30));
        orderMapper.insert(o);
        return new CreateResult(o.getOrderNo(), gateway().prepay(o.getOrderNo(), o.getAmountFen(), o.getSubject()),
                o.getExpireAt());
    }

    private PaymentGateway gateway() {
        PaymentGateway g = gateways.getIfAvailable();
        if (g == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "暂未开通在线支付，请联系客服");
        }
        return g;
    }

    /** 待支付订单「重新支付」（rePay）。 */
    public CreateResult repay(Long userId, String orderNo) {
        PayOrder o = mine(userId, orderNo);
        BizException.throwIf(!Status.PENDING.name().equals(o.getOrderStatus()) || o.getExpireAt().isBefore(LocalDateTime.now()),
                ErrorCode.STATE_TRANSITION_DENIED, "订单已失效，请重新下单");
        return new CreateResult(o.getOrderNo(), gateway().prepay(o.getOrderNo(), o.getAmountFen(), o.getSubject()),
                o.getExpireAt());
    }

    @Transactional
    public void cancel(Long userId, String orderNo) {
        transit(mine(userId, orderNo), Event.CANCEL);
    }

    /**
     * 支付回调：验签 → 状态 CAS（PENDING→PAID）→ 发币（业务键 = 订单号，重复回调不重复发币）。
     */
    @Transactional
    public void onPaid(String orderNo, String channelTxn, String sign) {
        if (!gateway().verifyNotify(orderNo, channelTxn, sign)) {
            throw new BizException(ErrorCode.FORBIDDEN, "签名校验失败");
        }
        PayOrder o = BizException.notNull(orderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getOrderNo, orderNo)), "订单");
        if (Status.PAID.name().equals(o.getOrderStatus())) {
            return;
        }
        if (Status.CANCELLED.name().equals(o.getOrderStatus())) {
            // 超时取消后才到账：仍然入账，避免用户付了钱拿不到币；同时记录以便对账
            log.warn("已取消订单收到支付回调，按已支付处理: {}", orderNo);
        } else {
            FSM.fire(Status.valueOf(o.getOrderStatus()), Event.PAY);
        }
        int changed = orderMapper.update(Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getOrderStatus, Status.PAID.name()).set(PayOrder::getPaidAt, LocalDateTime.now())
                .set(PayOrder::getChannelTxn, channelTxn)
                .eq(PayOrder::getId, o.getId()).eq(PayOrder::getOrderStatus, o.getOrderStatus()));
        if (changed == 0) {
            return;
        }
        accountService.grantCoins(o.getUserId(), o.getCoins(), "RECHARGE", o.getOrderNo(), o.getSubject());
        notifyApi.send(List.of(o.getUserId()), "ORDER", "充值成功", o.getSubject() + " 已到账", "order:" + o.getOrderNo());
    }

    public PageResult<PayOrder> myOrders(Long userId, String status, PageQuery q) {
        return PageResult.of(orderMapper.selectPage(q.toPage(), Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getUserId, userId).eq(status != null, PayOrder::getOrderStatus, status)
                .orderByDesc(PayOrder::getCreatedAt)));
    }

    /** 待支付超过 30 分钟自动取消。 */
    @Scheduled(fixedDelay = 60_000)
    public void cancelExpired() {
        List<PayOrder> expired = orderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getOrderStatus, Status.PENDING.name()).lt(PayOrder::getExpireAt, LocalDateTime.now())
                .last("LIMIT 500"));
        for (PayOrder o : expired) {
            orderMapper.update(Wrappers.<PayOrder>lambdaUpdate().set(PayOrder::getOrderStatus, Status.CANCELLED.name())
                    .set(PayOrder::getCancelledAt, LocalDateTime.now())
                    .eq(PayOrder::getId, o.getId()).eq(PayOrder::getOrderStatus, Status.PENDING.name()));
        }
    }

    private PayOrder mine(Long userId, String orderNo) {
        PayOrder o = orderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getOrderNo, orderNo));
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return o;
    }

    private void transit(PayOrder o, Event e) {
        Status next = FSM.fire(Status.valueOf(o.getOrderStatus()), e);
        int changed = orderMapper.update(Wrappers.<PayOrder>lambdaUpdate().set(PayOrder::getOrderStatus, next.name())
                .set(next == Status.CANCELLED, PayOrder::getCancelledAt, LocalDateTime.now())
                .eq(PayOrder::getId, o.getId()).eq(PayOrder::getOrderStatus, o.getOrderStatus()));
        BizException.throwIf(changed == 0, ErrorCode.CONFLICT);
    }
}
