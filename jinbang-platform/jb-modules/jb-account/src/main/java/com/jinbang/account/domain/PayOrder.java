package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 充值订单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_order")
public class PayOrder extends BaseEntity {
    private String orderNo;
    private Long userId;
    private String orderType;
    private String subject;
    private Long coins;
    private Long amountFen;
    /** PENDING / PAID / CANCELLED / REFUNDING / REFUNDED */
    private String orderStatus;
    private String payChannel;
    private String channelTxn;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;
    private String refundReason;
}
