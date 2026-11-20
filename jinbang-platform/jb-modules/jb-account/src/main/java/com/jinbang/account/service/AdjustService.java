package com.jinbang.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.account.domain.CoinAdjust;
import com.jinbang.account.domain.CoinFreeze;
import com.jinbang.account.domain.CoinLedger;
import com.jinbang.account.mapper.CoinAdjustMapper;
import com.jinbang.account.mapper.CoinFreezeMapper;
import com.jinbang.account.mapper.CoinLedgerMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 运营流水对账（AC-12）与人工调账（D4：可人工调账，但要审批，申请人和审批人不能是同一人）。 */
@Service
@RequiredArgsConstructor
public class AdjustService {
    private final CoinAdjustMapper adjustMapper;
    private final CoinLedgerMapper ledgerMapper;
    private final CoinFreezeMapper freezeMapper;
    private final AccountService accountService;

    public record TaskFlow(CoinFreeze freeze, List<CoinLedger> ledgers) {
    }

    /** 按任务 ID 查运营流水：冻结单 + 全部流水。 */
    public TaskFlow flowOfTask(String taskId) {
        return new TaskFlow(freezeMapper.selectOne(Wrappers.<CoinFreeze>lambdaQuery().eq(CoinFreeze::getTaskId, taskId)),
                ledgerMapper.selectList(Wrappers.<CoinLedger>lambdaQuery().eq(CoinLedger::getBizId, taskId)
                        .orderByAsc(CoinLedger::getCreatedAt)));
    }

    @Transactional
    @OperLog(module = "财务-调账", action = "申请调账")
    public CoinAdjust request(Long userId, long amount, String reason, String refTaskId) {
        BizException.throwIf(amount == 0 || reason == null || reason.isBlank(), ErrorCode.BAD_REQUEST, "请填写金额和原因");
        CoinAdjust a = new CoinAdjust();
        a.setUserId(userId);
        a.setAmount(amount);
        a.setReason(reason);
        a.setRefTaskId(refTaskId);
        a.setAdjustStatus("PENDING");
        adjustMapper.insert(a);
        return a;
    }

    @Transactional
    @OperLog(module = "财务-调账", action = "审批调账")
    public void approve(Long id, boolean pass) {
        CoinAdjust a = BizException.notNull(adjustMapper.selectById(id), "调账单");
        BizException.throwIf(!"PENDING".equals(a.getAdjustStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该调账单已处理");
        Long approver = LoginHelper.userId();
        BizException.throwIf(approver.equals(a.getCreatedBy()), ErrorCode.FORBIDDEN, "申请人不能审批自己的调账单");
        a.setAdjustStatus(pass ? "APPROVED" : "REJECTED");
        a.setApprovedBy(approver);
        a.setApprovedAt(LocalDateTime.now());
        adjustMapper.updateById(a);
        if (pass) {
            String bizId = "ADJ" + a.getId();
            if (a.getAmount() > 0) {
                accountService.grantCoins(a.getUserId(), a.getAmount(), "ADJUST", bizId, a.getReason());
            } else {
                accountService.spendCoins(a.getUserId(), -a.getAmount(), "ADJUST", bizId, a.getReason());
            }
        }
    }

    public List<CoinAdjust> pending() {
        return adjustMapper.selectList(Wrappers.<CoinAdjust>lambdaQuery().eq(CoinAdjust::getAdjustStatus, "PENDING"));
    }
}
