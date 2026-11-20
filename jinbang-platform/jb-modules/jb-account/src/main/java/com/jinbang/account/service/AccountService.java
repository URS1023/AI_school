package com.jinbang.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.account.domain.CoinAccount;
import com.jinbang.account.domain.CoinFreeze;
import com.jinbang.account.domain.CoinLedger;
import com.jinbang.account.domain.ExpLedger;
import com.jinbang.account.mapper.CoinAccountMapper;
import com.jinbang.account.mapper.CoinFreezeMapper;
import com.jinbang.account.mapper.CoinLedgerMapper;
import com.jinbang.account.mapper.ExpLedgerMapper;
import com.jinbang.api.account.AccountApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 金币账户（技术路线 3.3：冻结 → 结算，taskId 幂等）。
 * <p>
 * 幂等的实现：每次变动都写一条流水，流水的 (biz_type, biz_id, direction) 唯一。
 * 余额更新和流水写入在同一个事务里；如果并发重复请求撞上唯一键，整个事务回滚，按「已处理」返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService implements AccountApi {
    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";

    private final CoinAccountMapper accountMapper;
    private final CoinLedgerMapper ledgerMapper;
    private final CoinFreezeMapper freezeMapper;
    private final ExpLedgerMapper expMapper;
    private final PlatformTransactionManager txManager;

    /**
     * 加入调用方事务（注册送币要和建号同生共死）；没有外层事务时自己开一个。
     * 不用 REQUIRES_NEW：外层事务刚插入的账户行还没提交，新事务去更新它会互相等锁。
     */
    private TransactionTemplate newTx() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return t;
    }

    /** 撞唯一键视为并发重复请求，返回 onDuplicate 的结果（常规重复请求在进入事务前已被流水预查拦下）。 */
    private <T> T idempotent(Supplier<T> action, Supplier<T> onDuplicate) {
        try {
            return newTx().execute(s -> action.get());
        } catch (DuplicateKeyException e) {
            return onDuplicate.get();
        }
    }

    public CoinAccount ensureAccount(Long userId) {
        CoinAccount a = accountMapper.selectByUser(userId);
        if (a != null) {
            return a;
        }
        try {
            a = new CoinAccount();
            a.setUserId(userId);
            a.setBalance(0L);
            a.setFrozen(0L);
            a.setTotalIncome(0L);
            a.setTotalSpent(0L);
            a.setExp(0L);
            accountMapper.insert(a);
            return a;
        } catch (DuplicateKeyException e) {
            return accountMapper.selectByUser(userId);
        }
    }

    @Override
    public long balance(Long userId) {
        return ensureAccount(userId).getBalance();
    }

    private boolean ledgerExists(String bizType, String bizId, String direction) {
        return ledgerMapper.exists(Wrappers.<CoinLedger>lambdaQuery().eq(CoinLedger::getBizType, bizType)
                .eq(CoinLedger::getBizId, bizId).eq(CoinLedger::getDirection, direction));
    }

    private void writeLedger(Long userId, String bizType, String bizId, String direction, long amount, String remark) {
        CoinAccount a = accountMapper.selectByUser(userId);
        CoinLedger l = new CoinLedger();
        l.setUserId(userId);
        l.setBizType(bizType);
        l.setBizId(bizId);
        l.setDirection(direction);
        l.setAmount(amount);
        l.setBalanceAfter(a.getBalance());
        l.setFrozenAfter(a.getFrozen());
        l.setRemark(remark);
        ledgerMapper.insert(l);
    }

    @Override
    public void grantCoins(Long userId, long amount, String bizType, String bizId, String remark) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
        ensureAccount(userId);
        if (ledgerExists(bizType, bizId, IN)) {
            return;
        }
        idempotent(() -> {
            accountMapper.income(userId, amount);
            writeLedger(userId, bizType, bizId, IN, amount, remark);
            return null;
        }, () -> null);
    }

    @Override
    public void spendCoins(Long userId, long amount, String bizType, String bizId, String remark) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
        ensureAccount(userId);
        if (ledgerExists(bizType, bizId, OUT)) {
            return;
        }
        idempotent(() -> {
            if (accountMapper.spend(userId, amount) == 0) {
                throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            }
            writeLedger(userId, bizType, bizId, OUT, amount, remark);
            return null;
        }, () -> null);
    }

    @Override
    public void freeze(Long userId, String taskId, long amount, String bizType, String remark) {
        if (amount < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额不能为负");
        }
        ensureAccount(userId);
        if (freezeMapper.exists(Wrappers.<CoinFreeze>lambdaQuery().eq(CoinFreeze::getTaskId, taskId))) {
            return;
        }
        idempotent(() -> {
            if (amount > 0 && accountMapper.freeze(userId, amount) == 0) {
                throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            }
            CoinFreeze f = new CoinFreeze();
            f.setTaskId(taskId);
            f.setUserId(userId);
            f.setBizType(bizType);
            f.setAmount(amount);
            f.setFreezeStatus("FROZEN");
            f.setRemark(remark);
            freezeMapper.insert(f);
            if (amount > 0) {
                writeLedger(userId, bizType, taskId, FREEZE, amount, "预计消耗 " + amount + " 币（冻结）");
            }
            return null;
        }, () -> null);
    }

    @Override
    public long settle(String taskId, long actual, String remark) {
        CoinFreeze f = freezeMapper.selectOne(Wrappers.<CoinFreeze>lambdaQuery().eq(CoinFreeze::getTaskId, taskId));
        if (f == null) {
            throw new BizException(ErrorCode.BILLING_STATE_ERROR, "计费单不存在");
        }
        if ("SETTLED".equals(f.getFreezeStatus())) {
            return f.getCharged();
        }
        long charge = Math.min(Math.max(actual, 0), f.getAmount());
        long refund = f.getAmount() - charge;
        Long result = idempotent(() -> {
            // CAS：只有第一个把状态从 FROZEN 改成 SETTLED 的调用继续往下走
            int changed = freezeMapper.update(Wrappers.<CoinFreeze>lambdaUpdate()
                    .set(CoinFreeze::getFreezeStatus, "SETTLED").set(CoinFreeze::getCharged, charge)
                    .set(CoinFreeze::getSettledAt, LocalDateTime.now())
                    .eq(CoinFreeze::getId, f.getId()).eq(CoinFreeze::getFreezeStatus, "FROZEN"));
            if (changed == 0) {
                return null;
            }
            if (f.getAmount() > 0 && accountMapper.settle(f.getUserId(), f.getAmount(), charge) == 0) {
                throw new BizException(ErrorCode.BILLING_STATE_ERROR);
            }
            if (charge > 0) {
                writeLedger(f.getUserId(), f.getBizType(), taskId, OUT, charge, remark);
            }
            if (refund > 0) {
                writeLedger(f.getUserId(), f.getBizType(), taskId, UNFREEZE, refund,
                        charge == 0 ? "未扣费，全额解冻" : "按实际用量结算，退回差额");
            }
            return charge;
        }, () -> null);
        if (result == null) {
            CoinFreeze again = freezeMapper.selectById(f.getId());
            return again.getCharged() == null ? 0 : again.getCharged();
        }
        return result;
    }

    @Override
    public void addExp(Long userId, long amount, String bizType, String bizId, String remark) {
        ensureAccount(userId);
        if (expMapper.exists(Wrappers.<ExpLedger>lambdaQuery().eq(ExpLedger::getBizType, bizType)
                .eq(ExpLedger::getBizId, bizId))) {
            return;
        }
        idempotent(() -> {
            accountMapper.addExp(userId, amount);
            ExpLedger l = new ExpLedger();
            l.setUserId(userId);
            l.setBizType(bizType);
            l.setBizId(bizId);
            l.setAmount(amount);
            l.setExpAfter(accountMapper.selectByUser(userId).getExp());
            l.setRemark(remark);
            expMapper.insert(l);
            return null;
        }, () -> null);
    }
}
