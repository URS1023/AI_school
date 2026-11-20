package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库文件 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_doc")
public class AiKbDoc extends BaseEntity {
    private Long kbId;
    private String folder;
    private String fileName;
    private String storageKey;
    private Long sizeBytes;
    /** PARSING / READY / FAILED */
    private String parseStatus;
    private String parseError;
    private Integer chunkCount;
}
