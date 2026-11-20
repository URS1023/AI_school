package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 作文批改档案 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_essay_record")
public class AiEssayRecord extends BaseEntity {
    private Long userId;
    private Long studentId;
    private String studentName;
    private String taskId;
    private Integer itemNo;
    private String subject;
    private String genre;
    private String essayTitle;
    private Integer fullScore;
    private String essayText;
    private BigDecimal score;
    private String report;
}
