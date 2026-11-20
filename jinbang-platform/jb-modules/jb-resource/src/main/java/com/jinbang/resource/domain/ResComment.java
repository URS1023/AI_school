package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 评论 / 投诉 / 纠错 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_comment")
public class ResComment extends BaseEntity {
    private Long resourceId;
    private Long userId;
    /** COMMENT / COMPLAIN / ERROR */
    private String commentType;
    private String content;
    private String commentStatus;
    private String reply;
}
