package com.jinbang.teaching.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.TenantEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 集体备课主题 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tc_collective_theme")
public class TcCollectiveTheme extends TenantEntity {
    private Long organizerUserId;
    private String title;
    private String subject;
    private String description;
    private String memberUserIds;
    private Long mainMaterialId;
    /** ONGOING / FINISHED */
    private String themeStatus;
    private LocalDateTime deadline;
    private LocalDateTime finishedAt;
}
