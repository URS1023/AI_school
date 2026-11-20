package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 游客加入学校申请 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_join_request")
public class EduJoinRequest extends TenantEntity {
    private Long userId;
    private String realName;
    private String subject;
    private String remark;
    /** PENDING / APPROVED / REJECTED */
    private String reqStatus;
    private String rejectReason;
    private Long decidedBy;
    private LocalDateTime decidedAt;
}
