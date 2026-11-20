package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 学生在班记录 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_student_class_log")
public class EduStudentClassLog extends TenantEntity {
    private Long studentId;
    private Long classId;
    private Long yearId;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
}
