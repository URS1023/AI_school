package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户反馈 / 工单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("op_feedback")
public class OpFeedback extends BaseEntity {
    private Long userId;
    private String feedbackType;
    private String sourceApp;
    private String contact;
    private String content;
    private String images;
    private String remark;
    /** OPEN / REPLIED / CLOSED */
    private String fbStatus;
    private String reply;
    private Long repliedBy;
    private LocalDateTime repliedAt;
}
