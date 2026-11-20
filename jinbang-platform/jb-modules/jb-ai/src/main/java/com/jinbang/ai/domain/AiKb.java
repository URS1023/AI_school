package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb")
public class AiKb extends BaseEntity {
    private Long ownerUserId;
    /** PERSONAL / SHARED */
    private String kbType;
    private String kbName;
    private String intro;
    private Long quotaBytes;
}
