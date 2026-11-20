package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 冻结单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_freeze")
public class CoinFreeze extends BaseEntity {
    private String taskId;
    private Long userId;
    private String bizType;
    private Long amount;
    private Long charged;
    /** FROZEN / SETTLED */
    private String freezeStatus;
    private String remark;
    private LocalDateTime settledAt;
}
