package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 题目（统一模型） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("q_question")
public class QQuestion extends BaseEntity {
    /** PLATFORM / SCHOOL / PERSONAL */
    @JsonProperty("qScope")
    private String qScope;
    private Long tenantId;
    private Long ownerUserId;
    private Long refQuestionId;
    private String period;
    private String subject;
    @JsonProperty("qType")
    private String qType;
    private String difficulty;
    @JsonProperty("qSource")
    private String qSource;
    private String category;
    private String stem;
    private String options;
    private String answer;
    private String analysis;
    private String knowledge;
    private Long catalogId;
    private Long groupId;
    private Integer groupNo;
    @JsonProperty("qYear")
    private Integer qYear;
    private String regionCode;
    private Long usedCount;
    private Boolean deleted;
}
