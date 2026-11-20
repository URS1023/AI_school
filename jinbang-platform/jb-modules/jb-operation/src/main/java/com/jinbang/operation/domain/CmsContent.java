package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Banner / 资讯 / 公告 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cms_content")
public class CmsContent extends BaseEntity {
    /** BANNER / NEWS / NOTICE */
    private String contentType;
    private String title;
    private String cover;
    private String link;
    private String body;
    /** DRAFT / PUBLISHED / OFFLINE */
    private String contentStatus;
    private Integer sortNo;
    private LocalDateTime publishAt;
    private Long viewCount;
}
