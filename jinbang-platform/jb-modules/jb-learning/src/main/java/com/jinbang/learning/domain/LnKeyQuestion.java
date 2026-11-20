package com.jinbang.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 讲评重点题 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ln_key_question")
public class LnKeyQuestion extends TenantEntity {
    private String sourceType;
    private Long sourceId;
    private String itemNo;
}
