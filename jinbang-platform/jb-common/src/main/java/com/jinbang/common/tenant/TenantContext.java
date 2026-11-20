package com.jinbang.common.tenant;

import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;

import java.util.function.Supplier;

/**
 * 租户上下文。默认取当前身份的 tenantId；定时任务、跨校统计等场景可以显式指定或临时忽略。
 * 没有登录、也没有显式指定时返回 {@link #NO_TENANT}，查询租户表时什么都查不到（默认拒绝）。
 */
public final class TenantContext {
    public static final long NO_TENANT = -1L;

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static long currentTenantId() {
        Long t = TENANT.get();
        if (t != null) {
            return t;
        }
        return LoginHelper.current().map(CurrentIdentity::tenantId).orElse(NO_TENANT);
    }

    public static boolean isIgnored() {
        if (Boolean.TRUE.equals(IGNORE.get())) {
            return true;
        }
        // 平台运营身份跨租户查看（操作本身另有权限码控制）
        return TENANT.get() == null && LoginHelper.current().map(CurrentIdentity::isPlatform).orElse(false);
    }

    public static <T> T withTenant(long tenantId, Supplier<T> action) {
        Long prev = TENANT.get();
        TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                TENANT.remove();
            } else {
                TENANT.set(prev);
            }
        }
    }

    public static void withTenant(long tenantId, Runnable action) {
        withTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T ignore(Supplier<T> action) {
        Boolean prev = IGNORE.get();
        IGNORE.set(true);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                IGNORE.remove();
            } else {
                IGNORE.set(prev);
            }
        }
    }

    public static void ignore(Runnable action) {
        ignore(() -> {
            action.run();
            return null;
        });
    }
}
