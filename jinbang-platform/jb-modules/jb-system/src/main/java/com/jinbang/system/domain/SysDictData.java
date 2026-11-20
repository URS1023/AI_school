package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_data")
public class SysDictData extends BaseEntity {
    private String dictType;
    private String dictLabel;
    private String dictValue;
    private String parentValue;
    private Integer sortNo;
    private String dictStatus;
}
