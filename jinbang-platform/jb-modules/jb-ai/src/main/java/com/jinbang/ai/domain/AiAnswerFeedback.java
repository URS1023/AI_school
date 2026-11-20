package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 回答点赞点踩 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_answer_feedback")
public class AiAnswerFeedback extends BaseEntity {
    private Long messageId;
    private Long userId;
    private Boolean liked;
    private String comment;
}
