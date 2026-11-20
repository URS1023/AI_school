package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 分批发布 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_publish")
public class ExPublish extends TenantEntity {
    private Long examId;
    private Long classId;
    /** SCORE / LEARNING */
    private String publishKind;
    private LocalDateTime publishedAt;
    private LocalDateTime withdrawnAt;
}
