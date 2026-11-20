package com.jinbang.common.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 操作日志 / 审计日志。敏感操作（改分、离职、重置学情、调账）必须标注。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {
    /** 模块，例如「考试-成绩」。 */
    String module();

    /** 动作，例如「发布后改分」。 */
    String action();
}
