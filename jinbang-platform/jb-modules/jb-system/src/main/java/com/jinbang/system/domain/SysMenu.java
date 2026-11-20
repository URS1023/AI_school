package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 菜单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {
    private Long parentId;
    private String menuName;
    private String path;
    private String component;
    private String permRequired;
    private String menuType;
    private String appCode;
    /** NORMAL / COMING_SOON */
    private String menuStatus;
    private Integer sortNo;
}
