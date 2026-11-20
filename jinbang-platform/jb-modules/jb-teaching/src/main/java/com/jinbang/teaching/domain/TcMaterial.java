package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 备课资料 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_material")
public class TcMaterial extends BaseEntity {
    private Long ownerUserId;
    private Long catalogId;
    private String fileName;
    /** PPTX / DOCX / PDF / VIDEO / OTHER */
    private String fileType;
    private String storageKey;
    private Long sizeBytes;
    /** UPLOAD / BLANK / RESOURCE / AI_PPT / AI_PLAN / COPY / COLLECTIVE */
    private String source;
    private String sourceRef;
    private Integer versionNo;
    private Boolean deleted;
    private LocalDateTime deletedAt;
}
