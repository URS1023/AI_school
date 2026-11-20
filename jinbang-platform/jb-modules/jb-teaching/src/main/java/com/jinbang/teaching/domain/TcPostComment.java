package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 帖子评论 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_post_comment")
public class TcPostComment extends BaseEntity {
    private Long postId;
    private Long userId;
    private String content;
    private String commentStatus;
}
