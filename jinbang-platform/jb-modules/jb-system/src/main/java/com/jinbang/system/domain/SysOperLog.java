package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 操作日志 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_oper_log")
public class SysOperLog extends BaseEntity {
    private Long tenantId;
    private Long userId;
    private Long identityId;
    private String module;
    private String action;
    private String method;
    private String args;
    private Boolean success;
    private String errorMsg;
    private Long costMs;
}
