package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 备课目录 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_prep_catalog")
public class TcPrepCatalog extends BaseEntity {
    private Long ownerUserId;
    private Long parentId;
    private String catalogName;
    private String period;
    private String subject;
    private String edition;
    private Integer sortNo;
}
