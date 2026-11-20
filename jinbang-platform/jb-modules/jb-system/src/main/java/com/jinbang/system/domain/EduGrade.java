package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 年级 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_grade")
public class EduGrade extends TenantEntity {
    private Long yearId;
    private String period;
    /** 1-12，高一=10 */
    private Integer gradeLevel;
    private String gradeName;
    /** ACTIVE / GRADUATED */
    private String gradeStatus;
}
