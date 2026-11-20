package com.jinbang.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 成绩分档设置 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ln_band_setting")
public class LnBandSetting extends TenantEntity {
    private String subject;
    private BigDecimal excellentRate;
    private BigDecimal goodRate;
    private BigDecimal passRate;
    private BigDecimal criticalRange;
}
