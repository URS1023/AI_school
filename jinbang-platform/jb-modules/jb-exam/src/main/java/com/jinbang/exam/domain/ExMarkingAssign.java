package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 阅卷分配 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_marking_assign")
public class ExMarkingAssign extends TenantEntity {
    private String sourceType;
    private Long sourceId;
    private String itemNo;
    private Long identityId;
}
