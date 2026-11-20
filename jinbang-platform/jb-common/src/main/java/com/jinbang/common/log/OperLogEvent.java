package com.jinbang.common.log;

import java.time.LocalDateTime;

public record OperLogEvent(
        Long tenantId,
        Long userId,
        Long identityId,
        String module,
        String action,
        String method,
        String args,
        boolean success,
        String error,
        long costMs,
        LocalDateTime at) {
}
