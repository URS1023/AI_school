package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库切片 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_chunk")
public class AiKbChunk extends BaseEntity {
    private Long kbId;
    private Long docId;
    private Integer chunkNo;
    private String content;
}
