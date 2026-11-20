package com.jinbang.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库协作成员 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_member")
public class AiKbMember extends BaseEntity {
    private Long kbId;
    private Long userId;
    /** OWNER / EDITOR / VIEWER */
    private String memberRole;
}
