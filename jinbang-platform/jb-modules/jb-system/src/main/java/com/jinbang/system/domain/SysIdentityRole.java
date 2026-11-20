package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 身份-角色 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_identity_role")
public class SysIdentityRole extends BaseEntity {
    private Long identityId;
    private Long roleId;
}
