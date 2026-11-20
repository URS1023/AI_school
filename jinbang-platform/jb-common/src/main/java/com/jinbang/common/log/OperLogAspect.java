package com.jinbang.common.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

/** 采集 {@link OperLog} 标注的方法，以事件形式发出，由系统模块落库。 */
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {
    private static final int MAX_ARGS = 2000;

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        Optional<CurrentIdentity> who = LoginHelper.current();
        Throwable error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            String args;
            try {
                args = objectMapper.writeValueAsString(Arrays.stream(pjp.getArgs())
                        .filter(a -> a == null || isSerializable(a)).toList());
            } catch (Exception e) {
                args = "[unserializable]";
            }
            if (args.length() > MAX_ARGS) {
                args = args.substring(0, MAX_ARGS);
            }
            publisher.publishEvent(new OperLogEvent(
                    who.map(CurrentIdentity::tenantId).orElse(null),
                    who.map(CurrentIdentity::userId).orElse(null),
                    who.map(CurrentIdentity::identityId).orElse(null),
                    operLog.module(), operLog.action(), pjp.getSignature().toShortString(), args,
                    error == null, error == null ? null : error.getMessage(),
                    System.currentTimeMillis() - start, LocalDateTime.now()));
        }
    }

    private boolean isSerializable(Object a) {
        String n = a.getClass().getName();
        return !(n.startsWith("jakarta.servlet") || n.startsWith("org.springframework.web.multipart"));
    }
}
