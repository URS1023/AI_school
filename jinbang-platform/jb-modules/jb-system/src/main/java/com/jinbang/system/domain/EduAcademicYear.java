package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 学年 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_academic_year")
public class EduAcademicYear extends TenantEntity {
    private String yearName;
    private Integer startYear;
    private Boolean isCurrent;
}
