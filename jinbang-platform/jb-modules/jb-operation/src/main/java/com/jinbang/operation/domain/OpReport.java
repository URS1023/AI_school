package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 举报 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("op_report")
public class OpReport extends BaseEntity {
    private Long reporterId;
    private String targetType;
    private Long targetId;
    private Long targetOwnerId;
    private String reason;
    /** PENDING / UPHELD / REJECTED */
    private String reportStatus;
    private String result;
    private Long handledBy;
    private LocalDateTime handledAt;
}
