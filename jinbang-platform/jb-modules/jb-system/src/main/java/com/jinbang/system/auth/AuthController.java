package com.jinbang.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.jinbang.common.core.R;
import com.jinbang.common.idempotent.RepeatSubmit;
import com.jinbang.system.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final CaptchaService captchaService;
    private final SmsCodeService smsCodeService;
    private final UserService userService;

    public record PasswordLoginReq(@NotBlank String account, @NotBlank String password, String captchaId,
                                   String captchaCode) {
    }

    public record SmsReq(@NotBlank String phone, @NotBlank String scene) {
    }

    public record SmsLoginReq(@NotBlank String phone, @NotBlank String code) {
    }

    public record RegisterReq(@NotBlank String phone, @NotBlank String code, @NotBlank String password,
                              String nickname) {
    }

    public record ResetReq(@NotBlank String phone, @NotBlank String code, @NotBlank String newPassword) {
    }

    public record ChangeReq(String oldPassword, String smsCode, @NotBlank String newPassword) {
    }

    public record IdentityReq(@NotNull Long identityId) {
    }

    public record TicketReq(@NotBlank String ticket) {
    }

    public record CodeReq(@NotBlank String code) {
    }

    @GetMapping("/captcha")
    public R<CaptchaService.Captcha> captcha() {
        return R.ok(captchaService.create());
    }

    @PostMapping("/login")
    public R<AuthService.LoginVO> login(@Valid @RequestBody PasswordLoginReq req) {
        return R.ok(authService.loginByPassword(req.account(), req.password(), req.captchaId(), req.captchaCode()));
    }

    @PostMapping("/sms/send")
    public R<Void> sendSms(@Valid @RequestBody SmsReq req) {
        smsCodeService.send(req.phone(), req.scene());
        return R.ok();
    }

    @PostMapping("/sms/login")
    public R<AuthService.LoginVO> smsLogin(@Valid @RequestBody SmsLoginReq req) {
        return R.ok(authService.loginBySms(req.phone(), req.code()));
    }

    @RepeatSubmit
    @PostMapping("/register")
    public R<AuthService.LoginVO> register(@Valid @RequestBody RegisterReq req) {
        return R.ok(authService.register(req.phone(), req.code(), req.password(), req.nickname()));
    }

    @PostMapping("/password/forgot")
    public R<Void> forgot(@Valid @RequestBody ResetReq req) {
        authService.resetPassword(req.phone(), req.code(), req.newPassword());
        return R.ok();
    }

    /** 登录后发改密/注销验证码（手机号取当前账号，不由前端传入）。 */
    @PostMapping("/sms/send-self")
    public R<Void> sendSelf(@Valid @RequestBody SmsReq req) {
        smsCodeService.send(userService.get(StpUtil.getLoginIdAsLong()).getPhone(), req.scene());
        return R.ok();
    }

    @PostMapping("/password/change")
    public R<Void> change(@Valid @RequestBody ChangeReq req) {
        authService.changePassword(req.oldPassword(), req.smsCode(), req.newPassword());
        return R.ok();
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    @GetMapping("/identities")
    public R<AuthService.LoginVO> identities() {
        return R.ok(authService.currentLogin(StpUtil.getTokenValue(), userService.get(StpUtil.getLoginIdAsLong())));
    }

    @PostMapping("/identity/switch")
    public R<AuthService.LoginVO> switchIdentity(@Valid @RequestBody IdentityReq req) {
        return R.ok(authService.switchIdentity(req.identityId()));
    }

    @PostMapping("/identity/default")
    public R<Void> defaultIdentity(@Valid @RequestBody IdentityReq req) {
        authService.setDefaultIdentity(req.identityId());
        return R.ok();
    }

    /** 主站获取一次性 ticket（PRD 2.1 getTicket）。 */
    @PostMapping("/sso/ticket")
    public R<AuthService.TicketVO> ticket() {
        return R.ok(authService.issueTicket());
    }

    /** AI 平台用 ticket 换登录态（PRD 2.1 ssoLogin）。 */
    @PostMapping("/sso/login")
    public R<AuthService.LoginVO> ssoLogin(@Valid @RequestBody TicketReq req) {
        return R.ok(authService.ssoLogin(req.ticket()));
    }

    @PostMapping("/cancel")
    public R<Void> cancel(@Valid @RequestBody CodeReq req) {
        authService.cancelAccount(req.code());
        return R.ok();
    }
}
