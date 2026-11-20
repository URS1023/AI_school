package com.jinbang.operation.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 站内消息 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_message")
public class MsgMessage extends BaseEntity {
    private Long userId;
    private String category;
    private String title;
    private String content;
    private String bizRef;
    private Boolean readFlag;
    private LocalDateTime readAt;
}
