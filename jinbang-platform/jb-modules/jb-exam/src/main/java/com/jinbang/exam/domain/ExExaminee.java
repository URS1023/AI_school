package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 考生 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_examinee")
public class ExExaminee extends TenantEntity {
    private Long examId;
    private Long studentId;
    private Long classId;
    /** NORMAL / ABSENT 缺考 / EXEMPT 免考 / VOID 作废 */
    private String examineeStatus;
}
