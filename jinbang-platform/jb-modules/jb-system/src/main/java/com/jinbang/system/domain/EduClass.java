package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 班级 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edu_class")
public class EduClass extends TenantEntity {
    private Long yearId;
    private Long gradeId;
    private String className;
    /** ADMIN 行政班 / TEACHING 教学班 */
    private String classType;
    private Long headTeacherIdentityId;
    /** 家长绑定邀请码 */
    private String parentInviteCode;
    /** 学年升级前的班级 */
    private Long prevClassId;
    private String classStatus;
}
