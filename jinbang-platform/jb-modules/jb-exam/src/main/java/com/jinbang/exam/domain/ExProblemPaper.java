package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 问题卷 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_problem_paper")
public class ExProblemPaper extends TenantEntity {
    private Long answerId;
    private String itemNo;
    /** UNCLEAR / WRONG_POSITION / SUSPECT_COPY / OTHER */
    private String problemType;
    private String remark;
    /** OPEN / HANDLED */
    private String problemStatus;
    private Long reporterId;
    private Long handledBy;
    private LocalDateTime handledAt;
}
