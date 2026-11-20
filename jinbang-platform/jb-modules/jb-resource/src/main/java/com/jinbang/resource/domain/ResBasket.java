package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 资源篮 / 试题篮 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_basket")
public class ResBasket extends BaseEntity {
    private Long userId;
    /** RESOURCE / QUESTION */
    private String itemType;
    private Long itemId;
    private Integer sortNo;
}
