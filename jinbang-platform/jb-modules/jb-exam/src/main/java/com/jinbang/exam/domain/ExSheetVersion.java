package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 答题卡版本 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_sheet_version")
public class ExSheetVersion extends TenantEntity {
    private Long sheetId;
    private Integer versionNo;
    private String layoutJson;
    private BigDecimal totalScore;
    private Boolean frozen;
    private Boolean locked;
}
