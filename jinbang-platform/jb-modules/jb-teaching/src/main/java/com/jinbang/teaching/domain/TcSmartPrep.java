package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 智能备课 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_smart_prep")
public class TcSmartPrep extends BaseEntity {
    private Long ownerUserId;
    private Long catalogId;
    private String topic;
    private String edition;
    private String course;
    private String requirement;
    private String outlineJson;
    private String templateCode;
    /** OUTLINE / GENERATED */
    private String prepStatus;
    private Long materialId;
    private Boolean rewarded;
}
