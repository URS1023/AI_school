package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 教师档案 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_teacher")
public class EduTeacher extends TenantEntity {
    private Long identityId;
    private Long userId;
    private String teacherName;
    private String phone;
    private String subjects;
    /** ACTIVE / RESIGNED */
    private String teacherStatus;
    private LocalDateTime resignedAt;
}
