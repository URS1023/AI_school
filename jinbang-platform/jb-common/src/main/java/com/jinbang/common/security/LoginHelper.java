package com.jinbang.common.security;

import cn.dev33.satoken.stp.StpUtil;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;

import java.util.Optional;
import java.util.function.Supplier;

/** 读取当前登录用户与身份。身份对象保存在 Sa-Token 的 token 会话里，切换身份只影响当前 token。 */
public final class LoginHelper {
    public static final String SESSION_IDENTITY = "identity";

    private static final ThreadLocal<CurrentIdentity> OVERRIDE = new ThreadLocal<>();

    private LoginHelper() {
    }

    public static Optional<CurrentIdentity> current() {
        CurrentIdentity o = OVERRIDE.get();
        if (o != null) {
            return Optional.of(o);
        }
        try {
            if (!StpUtil.isLogin()) {
                return Optional.empty();
            }
            return Optional.ofNullable((CurrentIdentity) StpUtil.getTokenSession().get(SESSION_IDENTITY));
        } catch (Exception e) {
            // 非 Web 线程（定时任务、异步事件）没有请求上下文
            return Optional.empty();
        }
    }

    public static CurrentIdentity require() {
        return current().orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }

    public static Long userId() {
        return require().userId();
    }

    public static void setIdentity(CurrentIdentity identity) {
        StpUtil.getTokenSession().set(SESSION_IDENTITY, identity);
    }

    /** 在没有 Web 会话的场景（定时任务、测试、异步回调）以指定身份执行。 */
    public static <T> T runAs(CurrentIdentity identity, Supplier<T> action) {
        CurrentIdentity prev = OVERRIDE.get();
        OVERRIDE.set(identity);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(prev);
            }
        }
    }

    public static void runAs(CurrentIdentity identity, Runnable action) {
        runAs(identity, () -> {
            action.run();
            return null;
        });
    }
}
