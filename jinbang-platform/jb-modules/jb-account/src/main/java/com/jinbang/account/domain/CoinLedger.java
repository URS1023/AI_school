package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 金币流水 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_ledger")
public class CoinLedger extends BaseEntity {
    private Long userId;
    private String bizType;
    private String bizId;
    /** IN 收入 / OUT 支出 / FREEZE 冻结 / UNFREEZE 解冻 */
    private String direction;
    private Long amount;
    private Long balanceAfter;
    private Long frozenAfter;
    private String remark;
}
