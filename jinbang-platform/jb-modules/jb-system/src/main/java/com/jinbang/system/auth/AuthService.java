package com.jinbang.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.jinbang.api.account.AccountApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.Constants;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.security.SecurityConfig;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.service.IdentityService;
import com.jinbang.system.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserService userService;
    private final IdentityService identityService;
    private final CaptchaService captchaService;
    private final SmsCodeService smsCodeService;
    private final SsoTicketService ssoTicketService;
    private final AccountApi accountApi;

    @Value("${jb.auth.max-login-fail:5}")
    private int maxLoginFail;
    @Value("${jb.auth.lock-minutes:15}")
    private int lockMinutes;
    @Value("${jb.auth.captcha-enabled:true}")
    private boolean captchaEnabled;
    @Value("${jb.sso.agent-url:}")
    private String agentUrl;

    public record IdentityVO(Long identityId, Long tenantId, String type, String displayName, boolean current,
                             boolean isDefault) {
    }

    public record LoginVO(String token, boolean mustChangePassword, Long currentIdentityId,
                          List<IdentityVO> identities) {
    }

    public record TicketVO(String ticket, String redirectUrl, long expiresInSeconds) {
    }

    /** 账号密码登录：失败 N 次锁定（PRD 2.1 ➕P1）。 */
    @Transactional(noRollbackFor = BizException.class)
    public LoginVO loginByPassword(String account, String password, String captchaId, String captchaCode) {
        if (captchaEnabled) {
            captchaService.verify(captchaId, captchaCode);
        }
        SysUser user = userService.findByAccount(account)
                .filter(u -> UserService.ACTIVE.equals(u.getUserStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.LOGIN_FAILED));
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!userService.matches(user, password)) {
            int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            if (fails >= maxLoginFail) {
                user.setLockedUntil(now.plusMinutes(lockMinutes));
                user.setLoginFailCount(0);
                userService.update(user);
                throw new BizException(ErrorCode.ACCOUNT_LOCKED);
            }
            user.setLoginFailCount(fails);
            userService.update(user);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        return establish(user);
    }

    @Transactional
    public LoginVO loginBySms(String phone, String code) {
        smsCodeService.verify(phone, "LOGIN", code);
        SysUser user = userService.findByPhone(phone)
                .filter(u -> UserService.ACTIVE.equals(u.getUserStatus()))
                .orElseThrow(() -> new BizException(ErrorCode.LOGIN_FAILED, "该手机号未注册"));
        return establish(user);
    }

    /** 注册：送 500 金榜币，归属「访客学校（默认）」，身份为游客教师（PRD 2.1）。 */
    @Transactional
    public LoginVO register(String phone, String code, String password, String nickname) {
        smsCodeService.verify(phone, "REGISTER", code);
        userService.checkStrength(password);
        SysUser user = userService.create(phone, null, nickname == null ? "教师" + phone.substring(7) : nickname,
                password, false);
        identityService.ensureVisitorIdentity(user.getId(), user.getNickname());
        accountApi.grantCoins(user.getId(), Constants.REGISTER_GIFT_COINS, "REGISTER", String.valueOf(user.getId()),
                "注册赠送");
        return establish(user);
    }

    /** 忘记密码（PRD 2.1 ➕P0：登录页入口）。 */
    @Transactional
    public void resetPassword(String phone, String code, String newPassword) {
        smsCodeService.verify(phone, "RESET", code);
        SysUser user = userService.findByPhone(phone).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该手机号未注册"));
        userService.setPassword(user, newPassword);
        StpUtil.logout(user.getId());
    }

    /** 修改密码：原密码或手机验证码二选一。完成后解除「首次登录强制改密」。 */
    @Transactional
    public void changePassword(String oldPassword, String smsCode, String newPassword) {
        SysUser user = userService.get(StpUtil.getLoginIdAsLong());
        if (smsCode != null && !smsCode.isBlank()) {
            smsCodeService.verify(user.getPhone(), "CHANGE_PWD", smsCode);
        } else if (!userService.matches(user, oldPassword)) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "原密码不正确");
        }
        if (userService.matches(user, newPassword)) {
            throw new BizException(ErrorCode.PASSWORD_WEAK, "新密码不能与原密码相同");
        }
        userService.setPassword(user, newPassword);
        StpUtil.getTokenSession().delete(SecurityConfig.SESSION_MUST_CHANGE);
    }

    public LoginVO switchIdentity(Long identityId) {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUserIdentity identity = identityService.get(identityId);
        if (!Objects.equals(identity.getUserId(), userId)) {
            throw new BizException(ErrorCode.IDENTITY_INVALID);
        }
        LoginHelper.setIdentity(identityService.buildContext(identity));
        SysUser user = userService.get(userId);
        user.setLastIdentityId(identityId);
        userService.update(user);
        return currentLogin(StpUtil.getTokenValue(), user);
    }

    /** R4：设置默认身份，下次登录直接进入。 */
    public void setDefaultIdentity(Long identityId) {
        SysUser user = userService.get(StpUtil.getLoginIdAsLong());
        SysUserIdentity identity = identityService.get(identityId);
        if (!Objects.equals(identity.getUserId(), user.getId())) {
            throw new BizException(ErrorCode.IDENTITY_INVALID);
        }
        user.setDefaultIdentityId(identityId);
        userService.update(user);
    }

    public TicketVO issueTicket() {
        CurrentIdentity ci = LoginHelper.require();
        String ticket = ssoTicketService.issue(ci.userId(), ci.identityId());
        return new TicketVO(ticket, agentUrl + "?ticket=" + ticket, 60);
    }

    /** AI 平台用 ticket 换取自己的登录态（身份与主站当时一致）。 */
    @Transactional
    public LoginVO ssoLogin(String ticket) {
        SsoTicketService.TicketPayload p = ssoTicketService.consume(ticket);
        SysUser user = userService.get(p.userId());
        if (!UserService.ACTIVE.equals(user.getUserStatus())) {
            throw new BizException(ErrorCode.TICKET_INVALID);
        }
        SysUserIdentity identity = identityService.get(p.identityId());
        if (!IdentityService.ACTIVE.equals(identity.getIdentityStatus())) {
            throw new BizException(ErrorCode.TICKET_INVALID);
        }
        StpUtil.login(user.getId());
        LoginHelper.setIdentity(identityService.buildContext(identity));
        return currentLogin(StpUtil.getTokenValue(), user);
    }

    public void logout() {
        StpUtil.logout();
    }

    /** 账号注销（PRD 2.1 ➕P1，个人信息保护法）：撤销全部身份，清空手机号和登录名。 */
    @Transactional
    public void cancelAccount(String smsCode) {
        SysUser user = userService.get(StpUtil.getLoginIdAsLong());
        smsCodeService.verify(user.getPhone(), "CANCEL", smsCode);
        for (SysUserIdentity identity : identityService.activeIdentities(user.getId())) {
            identityService.revoke(identity.getId(), "ACCOUNT_CANCELLED");
        }
        user.setUserStatus("CANCELLED");
        user.setPhone(null);
        user.setUsername(null);
        user.setNickname("已注销用户");
        userService.update(user);
        StpUtil.logout(user.getId());
    }

    private LoginVO establish(SysUser user) {
        List<SysUserIdentity> found = identityService.activeIdentities(user.getId());
        final List<SysUserIdentity> identities = found.isEmpty()
                ? List.of(identityService.ensureVisitorIdentity(user.getId(), user.getNickname())) : found;
        SysUserIdentity chosen = pick(identities, user.getDefaultIdentityId())
                .or(() -> pick(identities, user.getLastIdentityId()))
                .orElse(identities.getFirst());
        StpUtil.login(user.getId());
        LoginHelper.setIdentity(identityService.buildContext(chosen));
        if (Boolean.TRUE.equals(user.getMustChangePwd())) {
            StpUtil.getTokenSession().set(SecurityConfig.SESSION_MUST_CHANGE, true);
        }
        boolean firstToday = user.getLastLoginAt() == null || user.getLastLoginAt().toLocalDate().isBefore(LocalDate.now());
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastIdentityId(chosen.getId());
        userService.update(user);
        userService.clearLock(user.getId());
        if (firstToday) {
            // 每日首次登录 +2 经验（PRD 2.4），业务键按日期幂等
            accountApi.addExp(user.getId(), 2, "LOGIN_DAILY", user.getId() + ":" + LocalDate.now(), "每日首次登录");
        }
        return currentLogin(StpUtil.getTokenValue(), user);
    }

    private java.util.Optional<SysUserIdentity> pick(List<SysUserIdentity> list, Long id) {
        return id == null ? java.util.Optional.empty() : list.stream().filter(i -> i.getId().equals(id)).findFirst();
    }

    public LoginVO currentLogin(String token, SysUser user) {
        CurrentIdentity ci = LoginHelper.require();
        List<IdentityVO> vos = identityService.activeIdentities(user.getId()).stream()
                .map(i -> new IdentityVO(i.getId(), i.getTenantId(), i.getIdentityType(), i.getDisplayName(),
                        i.getId().equals(ci.identityId()), i.getId().equals(user.getDefaultIdentityId())))
                .toList();
        return new LoginVO(token, Boolean.TRUE.equals(user.getMustChangePwd()), ci.identityId(), vos);
    }
}
