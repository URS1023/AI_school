package com.jinbang.common.idempotent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/** 单机版实现；拆分部署后换成 Redis SET NX PX。 */
@Aspect
@Component
public class RepeatSubmitAspect {
    private final Cache<String, Long> seen = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).maximumSize(100_000).build();

    @Around("@annotation(rs)")
    public Object around(ProceedingJoinPoint pjp, RepeatSubmit rs) throws Throwable {
        String who = LoginHelper.current().map(CurrentIdentity::userId).map(String::valueOf).orElse("anon");
        String key = who + ":" + pjp.getSignature().toLongString() + ":" + Arrays.deepHashCode(pjp.getArgs());
        long now = System.currentTimeMillis();
        Long last = seen.asMap().putIfAbsent(key, now);
        if (last != null) {
            if (now - last < rs.intervalMs()) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "请勿重复提交");
            }
            seen.put(key, now);
        }
        return pjp.proceed();
    }
}
