package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 学生 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_student")
public class EduStudent extends TenantEntity {
    private String studentNo;
    private String studentName;
    private String gender;
    private Long classId;
    private Integer enrollYear;
    /** 学籍号 */
    private String registerNo;
    /** 卡号（用途待确认） */
    private String cardNo;
    private Long userId;
    /** ACTIVE / TRANSFERRED / GRADUATED */
    private String studentStatus;
}
