package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 子任务（批量批改的每一篇） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_subtask")
public class AiSubtask extends BaseEntity {
    private String taskId;
    private Integer itemNo;
    private String itemLabel;
    private String inputText;
    private String outputText;
    private String itemStatus;
    private Integer attempts;
    private Integer units;
    private Long charged;
    private String failMessage;
}
