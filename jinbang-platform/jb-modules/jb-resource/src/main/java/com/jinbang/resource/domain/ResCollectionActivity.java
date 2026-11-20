package com.jinbang.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 资源征集活动 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_collection_activity")
public class ResCollectionActivity extends BaseEntity {
    private String title;
    private String cover;
    private String rules;
    private String periods;
    private String subjects;
    private Long rewardCoins;
    private Long rewardExp;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String activityStatus;
}
