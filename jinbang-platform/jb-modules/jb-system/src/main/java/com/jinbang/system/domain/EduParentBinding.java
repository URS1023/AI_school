package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 家长绑定 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_parent_binding")
public class EduParentBinding extends TenantEntity {
    private Long parentUserId;
    private Long identityId;
    private Long studentId;
    private String relation;
    /** BOUND / UNBOUND */
    private String bindStatus;
    private LocalDateTime unboundAt;
    private Long unboundBy;
}
