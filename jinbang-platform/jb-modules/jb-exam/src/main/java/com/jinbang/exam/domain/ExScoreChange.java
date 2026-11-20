package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 改分记录 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_score_change")
public class ExScoreChange extends TenantEntity {
    private Long answerId;
    private Long studentId;
    private String itemNo;
    private BigDecimal oldScore;
    private BigDecimal newScore;
    private BigDecimal oldTotal;
    private BigDecimal newTotal;
    private String reason;
    private String changeKind;
    private Boolean afterPublish;
    private Boolean notified;
    private Long operatorId;
}
