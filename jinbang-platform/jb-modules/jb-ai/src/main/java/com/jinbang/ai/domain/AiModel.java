package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模型路由 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model")
public class AiModel extends BaseEntity {
    private String modelCode;
    private String provider;
    private String baseUrl;
    private String modelName;
    private String apiKeyRef;
    private Integer priority;
    private Boolean enabled;
    private Integer timeoutSec;
}
