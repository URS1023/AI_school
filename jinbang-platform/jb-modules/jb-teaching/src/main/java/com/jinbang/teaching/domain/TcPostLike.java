package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 点赞 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_post_like")
public class TcPostLike extends BaseEntity {
    private Long postId;
    private Long userId;
}
