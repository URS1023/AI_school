package com.jinbang.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.account.domain.CoinAccount;
import com.jinbang.account.domain.CoinLedger;
import com.jinbang.account.domain.ExpLedger;
import com.jinbang.account.domain.SignInRecord;
import com.jinbang.account.mapper.CoinLedgerMapper;
import com.jinbang.account.mapper.ExpLedgerMapper;
import com.jinbang.account.mapper.SignInRecordMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/** 成长体系：账户概览、金币明细（PRD 2.4 ➕P0）、经验流水、签到。 */
@Service
@RequiredArgsConstructor
public class GrowthService {
    public static final Map<String, String> BIZ_LABELS = Map.ofEntries(
            Map.entry("REGISTER", "注册赠送"), Map.entry("RECHARGE", "充值"), Map.entry("SIGN_IN", "签到"),
            Map.entry("TASK", "任务奖励"), Map.entry("SMART_PREP", "智能备课奖励"), Map.entry("AI_TASK", "AI 智能体"),
            Map.entry("RESOURCE", "资源兑换"), Map.entry("ADJUST", "人工调账"), Map.entry("REFUND", "退款"),
            Map.entry("LOGIN_DAILY", "每日登录"));

    private final AccountService accountService;
    private final CoinLedgerMapper ledgerMapper;
    private final ExpLedgerMapper expMapper;
    private final SignInRecordMapper signMapper;

    @Value("${jb.growth.sign-coins:5}")
    private long signCoins;
    @Value("${jb.growth.sign-exp:1}")
    private long signExp;

    public record Summary(long balance, long frozen, long exp, int levelNo, String levelName, String privilege,
                          long expToNext, boolean signedToday, int continuousDays) {
    }

    public record LedgerVO(Long id, String bizType, String bizLabel, String bizId, String direction, long amount,
                           long balanceAfter, String remark, java.time.LocalDateTime createdAt) {
    }

    public Summary summary(Long userId) {
        CoinAccount a = accountService.ensureAccount(userId);
        LevelRule.Level level = LevelRule.of(a.getExp());
        SignInRecord today = todaySign(userId);
        SignInRecord last = signMapper.selectOne(Wrappers.<SignInRecord>lambdaQuery().eq(SignInRecord::getUserId, userId)
                .orderByDesc(SignInRecord::getSignDate).last("LIMIT 1"));
        int continuous = last == null || last.getSignDate().isBefore(LocalDate.now().minusDays(1)) ? 0 : last.getContinuous();
        return new Summary(a.getBalance(), a.getFrozen(), a.getExp(), level.no(), level.name(), level.privilege(),
                LevelRule.toNext(a.getExp()), today != null, continuous);
    }

    /** 金币明细：收入 / 支出 / 冻结 / 解冻，关联业务和余额。 */
    public PageResult<LedgerVO> coinLedger(Long userId, String direction, PageQuery q) {
        var page = ledgerMapper.selectPage(q.toPage(), Wrappers.<CoinLedger>lambdaQuery()
                .eq(CoinLedger::getUserId, userId).eq(direction != null, CoinLedger::getDirection, direction)
                .orderByDesc(CoinLedger::getCreatedAt).orderByDesc(CoinLedger::getId));
        return PageResult.of(page, l -> new LedgerVO(l.getId(), l.getBizType(),
                BIZ_LABELS.getOrDefault(l.getBizType(), l.getBizType()), l.getBizId(), l.getDirection(), l.getAmount(),
                l.getBalanceAfter(), l.getRemark(), l.getCreatedAt()));
    }

    public PageResult<ExpLedger> expLedger(Long userId, PageQuery q) {
        return PageResult.of(expMapper.selectPage(q.toPage(), Wrappers.<ExpLedger>lambdaQuery()
                .eq(ExpLedger::getUserId, userId).orderByDesc(ExpLedger::getCreatedAt)));
    }

    public Summary signIn(Long userId) {
        LocalDate today = LocalDate.now();
        BizException.throwIf(todaySign(userId) != null, ErrorCode.CONFLICT, "今天已经签到过了");
        SignInRecord yesterday = signMapper.selectOne(Wrappers.<SignInRecord>lambdaQuery()
                .eq(SignInRecord::getUserId, userId).eq(SignInRecord::getSignDate, today.minusDays(1)));
        SignInRecord r = new SignInRecord();
        r.setUserId(userId);
        r.setSignDate(today);
        r.setContinuous(yesterday == null ? 1 : yesterday.getContinuous() + 1);
        try {
            signMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "今天已经签到过了");
        }
        String bizId = userId + ":" + today;
        if (signCoins > 0) {
            accountService.grantCoins(userId, signCoins, "SIGN_IN", bizId, "每日签到");
        }
        if (signExp > 0) {
            accountService.addExp(userId, signExp, "SIGN_IN", bizId, "每日签到");
        }
        return summary(userId);
    }

    private SignInRecord todaySign(Long userId) {
        return signMapper.selectOne(Wrappers.<SignInRecord>lambdaQuery().eq(SignInRecord::getUserId, userId)
                .eq(SignInRecord::getSignDate, LocalDate.now()));
    }
}
