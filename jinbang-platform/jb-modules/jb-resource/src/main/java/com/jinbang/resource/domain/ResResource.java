package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 资源 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_resource")
public class ResResource extends BaseEntity {
    /** PLATFORM / SCHOOL / PERSONAL */
    private String resScope;
    private Long tenantId;
    private Long authorUserId;
    private String title;
    private String resType;
    /** FREE / PREMIUM / EXCLUSIVE */
    private String resLevel;
    private Long priceCoins;
    private String period;
    private String subject;
    private Long catalogId;
    private String regionCode;
    private Integer resYear;
    private String fileKey;
    private String fileExt;
    private Long fileSize;
    private String summary;
    /** DRAFT / PENDING / ONLINE / OFFLINE / REJECTED */
    private String resStatus;
    private String rejectReason;
    /** 校本：ALL / GRADE / SUBJECT */
    private String publishRange;
    private String rangeValue;
    private Long activityId;
    private Long downloadCount;
    private Long viewCount;
    private LocalDateTime publishedAt;
}
