package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户身份（一个自然人多个身份） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_identity")
public class SysUserIdentity extends BaseEntity {
    private Long userId;
    private Long tenantId;
    private String identityType;
    private String displayName;
    private Long studentId;
    /** ACTIVE / REVOKED */
    private String identityStatus;
    private LocalDateTime revokedAt;
    private String revokeReason;
}
