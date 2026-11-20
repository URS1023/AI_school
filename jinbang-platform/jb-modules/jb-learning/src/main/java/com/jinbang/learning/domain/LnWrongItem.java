package com.jinbang.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 错题 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ln_wrong_item")
public class LnWrongItem extends TenantEntity {
    private Long studentId;
    private Long classId;
    private String sourceType;
    private Long sourceId;
    private String sourceTitle;
    private String subject;
    private String itemNo;
    private String itemType;
    private Long questionId;
    private BigDecimal score;
    private BigDecimal fullScore;
    private String studentAnswer;
    private Boolean mastered;
    private LocalDateTime masteredAt;
    private LocalDateTime occurredAt;
}
