package com.jinbang.common.core;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户（学校）级实体：tenant_id 由多租户插件在查询时自动拼接、插入时自动填充。 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantEntity extends BaseEntity {
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
}
