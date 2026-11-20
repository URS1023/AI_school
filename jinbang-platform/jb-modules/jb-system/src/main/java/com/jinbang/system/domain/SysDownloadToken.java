package com.jinbang.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 下载凭证 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_download_token")
public class SysDownloadToken extends BaseEntity {
    private String token;
    private Long userId;
    private Long identityId;
    private Long tenantId;
    private String storageKey;
    private String fileName;
    private LocalDateTime expireAt;
    private Boolean revoked;
}
