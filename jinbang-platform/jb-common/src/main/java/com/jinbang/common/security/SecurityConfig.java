package com.jinbang.common.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    public static final String SESSION_MUST_CHANGE = "mustChangePassword";

    /** 不需要登录的接口。 */
    public static final String[] OPEN_PATHS = {
            "/api/auth/captcha", "/api/auth/login", "/api/auth/sms/**", "/api/auth/register",
            "/api/auth/sso/login", "/api/auth/password/forgot", "/api/open/**",
            "/api/exam/recognition/callback"
    };

    /** 使用初始密码登录后仍然允许访问的接口（首次登录强制改密，PRD 2.1 ➕P0）。 */
    private static final String[] ALLOWED_BEFORE_PASSWORD_CHANGE = {
            "/api/auth/**", "/api/system/user/me"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // SaInterceptor 同时负责 @SaCheckPermission / @SaCheckRole 注解鉴权
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/api/**").notMatch(OPEN_PATHS).check(r -> StpUtil.checkLogin());
            SaRouter.match("/api/**").notMatch(OPEN_PATHS).notMatch(ALLOWED_BEFORE_PASSWORD_CHANGE)
                    .check(r -> {
                        if (Boolean.TRUE.equals(StpUtil.getTokenSession().get(SESSION_MUST_CHANGE))) {
                            throw new BizException(ErrorCode.MUST_CHANGE_PASSWORD);
                        }
                    });
        })).addPathPatterns("/**");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
