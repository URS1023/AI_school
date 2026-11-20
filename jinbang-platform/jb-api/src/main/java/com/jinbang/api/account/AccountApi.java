package com.jinbang.api.account;

/**
 * 账户契约（金币与经验）。账户按自然人唯一，不随身份切换（PRD 8.2）。
 * 所有写操作都必须幂等：同一个业务键重复调用只生效一次。
 */
public interface AccountApi {

    long balance(Long userId);

    /** 发放金币（注册赠送、任务奖励、退款等）。bizType + bizId 唯一。 */
    void grantCoins(Long userId, long amount, String bizType, String bizId, String remark);

    /** 扣除金币（资源兑换等一次性扣费）。余额不足抛 BALANCE_NOT_ENOUGH。bizType + bizId 唯一。 */
    void spendCoins(Long userId, long amount, String bizType, String bizId, String remark);

    /** 冻结预估金额（PRD 11.1）。同一 taskId 重复冻结直接返回。余额不足抛 BALANCE_NOT_ENOUGH。 */
    void freeze(Long userId, String taskId, long amount, String bizType, String remark);

    /**
     * 结算：实扣 min(actual, 冻结额)，其余解冻退回。同一 taskId 只结算一次，重复调用返回首次结果。
     * actual = 0 等价于全额解冻（失败、空结果、审核拦截都走这里）。
     *
     * @return 实际扣费金额
     */
    long settle(String taskId, long actual, String remark);

    /** 增加经验（每日首登 +2、智能备课 +20）。bizType + bizId 唯一。 */
    void addExp(Long userId, long amount, String bizType, String bizId, String remark);
}
