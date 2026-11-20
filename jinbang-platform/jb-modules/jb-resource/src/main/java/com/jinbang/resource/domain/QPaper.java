package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 试卷 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("q_paper")
public class QPaper extends BaseEntity {
    private Long ownerUserId;
    private Long tenantId;
    private String title;
    private String period;
    private String subject;
    private BigDecimal totalScore;
    /** MANUAL / SMART */
    private String paperMode;
    private String sealLine;
    private String folder;
    private Boolean deleted;
}
