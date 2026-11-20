package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 社区帖子 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_post")
public class TcPost extends BaseEntity {
    private String board;
    private Long schoolId;
    private Long authorUserId;
    private String authorName;
    private String title;
    private String content;
    private String images;
    private String attachmentKey;
    private String period;
    private String subject;
    /** PENDING / PUBLISHED / REJECTED / REMOVED */
    private String postStatus;
    private String rejectReason;
    private Integer likeCount;
    private Integer commentCount;
}
