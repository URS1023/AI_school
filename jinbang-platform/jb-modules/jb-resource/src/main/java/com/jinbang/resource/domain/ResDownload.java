package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 下载权益 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_download")
public class ResDownload extends BaseEntity {
    private Long userId;
    private Long resourceId;
    private Long paidCoins;
    private Integer times;
}
