package com.jinbang.common.security;

import java.io.Serializable;
import java.util.Set;

/**
 * 当前请求的身份上下文。账户（金币、经验）按自然人 userId 唯一；数据权限按 identityId。
 */
public record CurrentIdentity(
        Long userId,
        Long identityId,
        Long tenantId,
        IdentityType type,
        Set<String> roleKeys,
        Set<String> perms,
        DataScope dataScope,
        Long regionId) implements Serializable {

    public boolean hasRole(String roleKey) {
        return roleKeys != null && roleKeys.contains(roleKey);
    }

    public boolean hasPerm(String perm) {
        return perms != null && (perms.contains("*") || perms.contains(perm));
    }

    public boolean isPlatform() {
        return type == IdentityType.PLATFORM;
    }

    public boolean isVisitor() {
        return type == IdentityType.VISITOR_TEACHER;
    }
}
