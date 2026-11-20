package com.jinbang.common.core;

import lombok.Getter;

/** 业务异常：消息会原样返回给用户，所以只能写业务语言。 */
@Getter
public class BizException extends RuntimeException {
    private final int code;
    private final transient Object detail;

    public BizException(ErrorCode ec) {
        this(ec.code(), ec.msg(), null);
    }

    public BizException(ErrorCode ec, String msg) {
        this(ec.code(), msg, null);
    }

    public BizException(ErrorCode ec, String msg, Object detail) {
        this(ec.code(), msg, detail);
    }

    public BizException(int code, String msg, Object detail) {
        super(msg);
        this.code = code;
        this.detail = detail;
    }

    public static void throwIf(boolean condition, ErrorCode ec) {
        if (condition) {
            throw new BizException(ec);
        }
    }

    public static void throwIf(boolean condition, ErrorCode ec, String msg) {
        if (condition) {
            throw new BizException(ec, msg);
        }
    }

    public static <T> T notNull(T obj, String what) {
        if (obj == null) {
            throw new BizException(ErrorCode.NOT_FOUND, what + "不存在或已被删除");
        }
        return obj;
    }
}
