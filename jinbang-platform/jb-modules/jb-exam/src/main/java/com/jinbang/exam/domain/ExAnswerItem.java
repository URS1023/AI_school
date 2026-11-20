package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 逐题作答与得分 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_answer_item")
public class ExAnswerItem extends TenantEntity {
    private Long answerId;
    private String itemNo;
    private String itemType;
    private Boolean objective;
    private BigDecimal fullScore;
    private String stdAnswer;
    private String studentAnswer;
    private BigDecimal score;
    /** AUTO / RECOGNIZED / MANUAL / AI / IMPORT */
    private String scoreSource;
    private Boolean needsReview;
    private String reviewReason;
    private String aiComment;
    private Long markedBy;
    private LocalDateTime markedAt;
}
