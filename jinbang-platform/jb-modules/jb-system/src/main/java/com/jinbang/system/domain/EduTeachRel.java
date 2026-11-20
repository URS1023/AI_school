package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 任教关系 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_teach_rel")
public class EduTeachRel extends TenantEntity {
    private Long yearId;
    private Long identityId;
    private String relType;
    private Long gradeId;
    private Long classId;
    private String subject;
    /** ACTIVE / ENDED */
    private String relStatus;
    private LocalDateTime endedAt;
}
