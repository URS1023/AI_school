package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 任务（计费单元） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_task")
public class AiTask extends BaseEntity {
    private String taskId;
    private String parentTaskId;
    private Long userId;
    private Long identityId;
    private Long tenantId;
    private String agentCode;
    private Long sessionId;
    private String inputText;
    private Long estimate;
    private Long charged;
    private String taskStatus;
    private Integer totalItems;
    private Integer successItems;
    private String userMessage;
    private String failReason;
    private String modelCode;
    private Integer promptVersion;
    private Long tokensIn;
    private Long tokensOut;
    private Long costMs;
    private LocalDateTime finishedAt;
}
