package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 扫描批次 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_scan_batch")
public class ExScanBatch extends TenantEntity {
    private String sourceType;
    private Long sourceId;
    private String scanType;
    private Integer imageCount;
    private Integer recognizedCount;
    private Integer exceptionCount;
    private String batchStatus;
    private Long scannedBy;
}
