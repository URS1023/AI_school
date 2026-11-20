package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 推荐问题 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_recommend_question")
public class AiRecommendQuestion extends BaseEntity {
    private String agentCode;
    private String identityType;
    private String question;
    private Integer sortNo;
}
