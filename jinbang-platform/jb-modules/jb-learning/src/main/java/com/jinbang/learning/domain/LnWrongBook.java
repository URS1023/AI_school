package com.jinbang.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 错题本 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ln_wrong_book")
public class LnWrongBook extends TenantEntity {
    private Long ownerUserId;
    private String bookName;
    private String subject;
    private Long studentId;
    private String wrongIds;
    private Boolean sheetMade;
    private Long sheetId;
}
