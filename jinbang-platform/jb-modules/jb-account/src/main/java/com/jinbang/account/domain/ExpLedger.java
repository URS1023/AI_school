package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 经验流水 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exp_ledger")
public class ExpLedger extends BaseEntity {
    private Long userId;
    private String bizType;
    private String bizId;
    private Long amount;
    private Long expAfter;
    private String remark;
}
