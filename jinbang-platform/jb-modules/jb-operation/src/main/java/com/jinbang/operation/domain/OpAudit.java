package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 审核单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("op_audit")
public class OpAudit extends BaseEntity {
    private String bizType;
    private Long bizId;
    private Long tenantId;
    private String title;
    private String content;
    private Long submitterUserId;
    /** PASS / RISK */
    private String machineResult;
    private String machineHits;
    /** PENDING / APPROVED / REJECTED */
    private String auditStatus;
    private String reason;
    private Long auditorId;
    private LocalDateTime auditedAt;
}
