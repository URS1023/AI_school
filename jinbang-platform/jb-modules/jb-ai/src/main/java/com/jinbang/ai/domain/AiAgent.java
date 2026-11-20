package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 智能体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent")
public class AiAgent extends BaseEntity {
    private String agentCode;
    private String agentName;
    private String description;
    private String icon;
    private String category;
    /** PER_CALL / PER_KCHAR / PER_PAGE / PER_ITEM / FREE */
    private String priceMode;
    private Long unitPrice;
    private Integer defaultUnits;
    private Integer maxUnits;
    private String allowedTypes;
    /** ONLINE / OFFLINE / COMING_SOON */
    private String agentStatus;
    private String modelCode;
    private Integer sortNo;
}
