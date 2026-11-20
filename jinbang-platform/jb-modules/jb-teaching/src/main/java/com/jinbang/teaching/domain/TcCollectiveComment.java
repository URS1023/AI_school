package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 研讨评论 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_collective_comment")
public class TcCollectiveComment extends TenantEntity {
    private Long themeId;
    private Long userId;
    private String content;
}
