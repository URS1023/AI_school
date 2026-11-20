package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Prompt 版本 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_prompt")
public class AiPrompt extends BaseEntity {
    private String agentCode;
    private Integer versionNo;
    private String content;
    /** DRAFT / ACTIVE / GRAY / RETIRED */
    private String promptStatus;
    private Integer grayPercent;
    private String remark;
}
