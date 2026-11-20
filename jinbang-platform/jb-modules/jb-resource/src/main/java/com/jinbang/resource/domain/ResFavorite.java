package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 收藏 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_favorite")
public class ResFavorite extends BaseEntity {
    private Long userId;
    private String targetType;
    private Long targetId;
    private String folder;
}
