package com.jinbang.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 签到 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sign_in_record")
public class SignInRecord extends BaseEntity {
    private Long userId;
    private LocalDate signDate;
    private Integer continuous;
}
