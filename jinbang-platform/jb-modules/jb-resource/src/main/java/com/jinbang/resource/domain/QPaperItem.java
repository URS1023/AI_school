package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 试卷题目 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("q_paper_item")
public class QPaperItem extends BaseEntity {
    private Long paperId;
    private Long questionId;
    private String sectionTitle;
    private Integer itemNo;
    private BigDecimal score;
}
