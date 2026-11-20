package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 敏感词 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("op_sensitive_word")
public class OpSensitiveWord extends BaseEntity {
    private String word;
}
