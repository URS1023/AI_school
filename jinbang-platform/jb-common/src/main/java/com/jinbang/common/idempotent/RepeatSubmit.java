package com.jinbang.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交（同一用户、同一方法、同一参数在窗口期内只放行一次）。
 * 注意：这只是前置防抖，真正的幂等由业务唯一键保证（例如金币流水的 task_id + 类型）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatSubmit {
    long intervalMs() default 3000;
}
