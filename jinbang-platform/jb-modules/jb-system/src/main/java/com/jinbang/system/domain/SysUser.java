package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 自然人账号 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private String phone;
    private String username;
    private String nickname;
    private String avatar;
    private String password;
    /** 首次登录强制改密 */
    private Boolean mustChangePwd;
    /** ACTIVE / DISABLED / CANCELLED */
    private String userStatus;
    private Integer loginFailCount;
    private LocalDateTime lockedUntil;
    private Long defaultIdentityId;
    private Long lastIdentityId;
    private LocalDateTime lastLoginAt;
}
