package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 金币账户（按自然人唯一） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_account")
public class CoinAccount extends BaseEntity {
    private Long userId;
    private Long balance;
    private Long frozen;
    private Long totalIncome;
    private Long totalSpent;
    private Long exp;
}
