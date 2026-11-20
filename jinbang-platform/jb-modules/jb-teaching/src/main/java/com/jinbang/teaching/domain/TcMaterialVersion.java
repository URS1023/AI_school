package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 资料版本 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_material_version")
public class TcMaterialVersion extends BaseEntity {
    private Long materialId;
    private Integer versionNo;
    private String storageKey;
    private Long sizeBytes;
}
