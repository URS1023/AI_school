package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 考试 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_exam")
public class ExExam extends TenantEntity {
    private Long yearId;
    private String examName;
    /** SCHOOL / JOINT */
    private String examType;
    private Long gradeId;
    private String subject;
    private Long paperId;
    private Long sheetVersionId;
    private LocalDateTime examTime;
    private String examStatus;
    private Boolean scoreAuditRequired;
    private Boolean showRank;
    private String viewSettings;
    private Long creatorIdentityId;
}
