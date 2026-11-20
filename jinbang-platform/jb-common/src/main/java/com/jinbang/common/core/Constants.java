package com.jinbang.common.core;

public final class Constants {
    private Constants() {
    }

    /** 「访客学校（默认）」租户：自注册游客教师归属这里，没有任何学校数据权限。 */
    public static final long VISITOR_TENANT_ID = 0L;
    /** 平台运营租户。 */
    public static final long PLATFORM_TENANT_ID = 1L;
    /** 注册赠送金榜币（PRD 2.4）。 */
    public static final long REGISTER_GIFT_COINS = 500L;
    /** 学校导入教师/学生的初始密码（手册），首次登录强制修改。 */
    public static final String DEFAULT_PASSWORD = "123456";
    public static final String AI_DISCLAIMER = "内容由 AI 生成，仅供参考";
}
