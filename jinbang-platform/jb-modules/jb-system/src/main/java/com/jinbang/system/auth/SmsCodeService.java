package com.jinbang.system.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/** 短信验证码：60 秒内不能重发，5 分钟有效，一次性，最多试错 5 次。 */
@Slf4j
@Service
public class SmsCodeService {
    public static final Set<String> SCENES = Set.of("LOGIN", "REGISTER", "RESET", "CHANGE_PWD", "CANCEL", "BIND");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final SmsSender sender;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Long> lastSent = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).build();
    private final Cache<String, Entry> codes = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

    private static final class Entry {
        final String code;
        int attempts;

        Entry(String code) {
            this.code = code;
        }
    }

    public SmsCodeService(SmsSender sender) {
        this.sender = sender;
    }

    public void send(String phone, String scene) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (!SCENES.contains(scene)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的验证码场景");
        }
        String key = scene + ":" + phone;
        if (lastSent.asMap().putIfAbsent(key, System.currentTimeMillis()) != null) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证码已发送，请 60 秒后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        codes.put(key, new Entry(code));
        sender.sendCode(phone, scene, code);
    }

    public void verify(String phone, String scene, String code) {
        String key = scene + ":" + phone;
        Entry e = codes.getIfPresent(key);
        if (e == null || code == null) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID);
        }
        if (!e.code.equals(code.trim())) {
            if (++e.attempts >= 5) {
                codes.invalidate(key);
            }
            throw new BizException(ErrorCode.CAPTCHA_INVALID);
        }
        codes.invalidate(key);
    }

    @Configuration
    static class DefaultSender {
        @Bean
        @ConditionalOnMissingBean(SmsSender.class)
        SmsSender logSmsSender() {
            return (phone, scene, code) -> log.info("[短信-开发模式] {} 场景={} 验证码={}", phone, scene, code);
        }
    }
}
