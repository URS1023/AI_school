package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 答题卡 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_sheet")
public class ExSheet extends TenantEntity {
    private Long ownerUserId;
    private String sheetName;
    private String subject;
    private Long paperId;
    private String paperSize;
    /** CARD_ONLY 纯题卡 / WITH_QUESTION 题卡合一 */
    private String sheetStyle;
    /** SCAN_THEN_MARK 先扫后阅 / MARK_THEN_SCAN 先阅后扫 */
    private String markMode;
    private Integer studentNoDigits;
    /** FILL 涂填 / HANDWRITE 手写 / BARCODE 条形码 */
    private String noStyle;
    private Integer currentVersion;
}
