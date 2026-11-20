package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 目录树（教材 / 知识点 / 个人题库目录） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_catalog")
public class ResCatalog extends BaseEntity {
    /** TEXTBOOK / KNOWLEDGE / QUESTION_DIR */
    private String catalogType;
    private Long ownerUserId;
    private Long parentId;
    private String period;
    private String subject;
    private String edition;
    private String volume;
    private String catalogName;
    private Integer sortNo;
}
