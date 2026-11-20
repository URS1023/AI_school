package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 人工调账 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_adjust")
public class CoinAdjust extends BaseEntity {
    private Long userId;
    private Long amount;
    private String reason;
    private String refTaskId;
    /** PENDING / APPROVED / REJECTED */
    private String adjustStatus;
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
