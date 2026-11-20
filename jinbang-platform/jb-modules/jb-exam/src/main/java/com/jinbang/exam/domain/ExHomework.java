package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 作业 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_homework")
public class ExHomework extends TenantEntity {
    private Long yearId;
    private String title;
    /** OFFLINE 纸质 / ONLINE 线上 */
    private String hwMode;
    /** ANSWER_SHEET / WORKBOOK */
    private String scanType;
    private String subject;
    private Long paperId;
    private Long sheetVersionId;
    private String classIds;
    private String studentIds;
    private LocalDateTime deadline;
    private Boolean allowLate;
    private Boolean allowWithdraw;
    private Boolean assignToTeacher;
    private Boolean answerPublished;
    private String hwStatus;
    private Long creatorIdentityId;
    private LocalDateTime markedAt;
}
