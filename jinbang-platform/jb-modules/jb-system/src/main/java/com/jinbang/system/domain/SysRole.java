package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {
    private Long tenantId;
    private String roleKey;
    private String roleName;
    private String identityType;
    private String dataScope;
    /** 逗号分隔的权限码 */
    private String perms;
    private Integer sortNo;
}
