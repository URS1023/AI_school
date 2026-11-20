package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 答卷 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_answer")
public class ExAnswer extends TenantEntity {
    /** HOMEWORK / EXAM */
    private String sourceType;
    private Long sourceId;
    private Long studentId;
    private Long classId;
    private Long sheetVersionId;
    private Long batchId;
    private String pageRef;
    private String images;
    private String rawStudentNo;
    /** EXCEPTION / MARKING / MARKED / REPLACED */
    private String answerStatus;
    /** STUDENT_MISSING / STUDENT_NOT_FOUND / NOT_IN_LIST */
    private String exceptionReason;
    private Boolean isCurrent;
    private Long replacedBy;
    private Boolean lateFlag;
    private Boolean makeupFlag;
    private BigDecimal totalScore;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal fullScore;
    private LocalDateTime submittedAt;
}
