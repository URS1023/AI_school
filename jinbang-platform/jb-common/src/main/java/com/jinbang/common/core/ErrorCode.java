package com.jinbang.common.core;

/**
 * 统一错误码。面向用户的提示一律用业务语言，不暴露内部细节（PRD 13「错误处理」）。
 */
public enum ErrorCode {
    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "请先登录"),
    FORBIDDEN(403, "当前身份无权限，请联系学校管理员开通"),
    NOT_FOUND(404, "数据不存在或已被删除"),
    CONFLICT(409, "数据已被修改，请刷新后重试"),
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试"),
    SERVICE_UNAVAILABLE(503, "服务暂时不可用，请稍后再试"),

    CAPTCHA_INVALID(1001, "验证码错误或已过期"),
    LOGIN_FAILED(1002, "账号或密码错误"),
    ACCOUNT_LOCKED(1003, "登录失败次数过多，账号已临时锁定，请 15 分钟后再试"),
    MUST_CHANGE_PASSWORD(1004, "首次登录请先修改初始密码"),
    TICKET_INVALID(1005, "登录凭证已失效，请重新从主站进入"),
    PHONE_EXISTS(1006, "该手机号已注册"),
    IDENTITY_INVALID(1007, "该身份不可用"),
    PASSWORD_WEAK(1008, "密码需 8-20 位，且同时包含字母和数字"),

    BALANCE_NOT_ENOUGH(2001, "金榜币余额不足，请先充值"),
    BILLING_STATE_ERROR(2002, "计费状态异常"),

    STATE_TRANSITION_DENIED(3001, "当前状态不允许该操作"),
    SCORE_OUT_OF_RANGE(3002, "得分超出本题满分，已拒绝保存"),
    SHEET_LOCKED(3003, "答题卡已有扫描数据，修改将生成新版本"),
    IMPORT_INVALID(3004, "导入数据校验未通过"),

    CONTENT_BLOCKED(4001, "内容不合规，请修改后重试"),
    AI_FAILED(4002, "服务暂时不可用，本次未扣费");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }
}
