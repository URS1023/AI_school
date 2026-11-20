package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户（学校） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {
    private String tenantName;
    private String regionCode;
    private String periods;
    private String logo;
    private String packageCode;
    private LocalDateTime expireAt;
    /** 教师加入学校的邀请码（11.9） */
    private String inviteCode;
    private String tenantStatus;
}
