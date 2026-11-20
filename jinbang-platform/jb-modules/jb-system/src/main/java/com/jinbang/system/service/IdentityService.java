package com.jinbang.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.IdentityRevokedEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.Constants;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.DataScope;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.system.domain.SysRole;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.mapper.SysTenantMapper;
import com.jinbang.system.mapper.SysUserIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 多身份管理（PRD 8.2 R3–R6、8.3）。 */
@Service
@RequiredArgsConstructor
public class IdentityService {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    private final SysUserIdentityMapper identityMapper;
    private final SysTenantMapper tenantMapper;
    private final RoleService roleService;
    private final ApplicationEventPublisher events;

    public SysUserIdentity get(Long identityId) {
        return BizException.notNull(identityMapper.selectById(identityId), "身份");
    }

    public List<SysUserIdentity> activeIdentities(Long userId) {
        return identityMapper.selectList(Wrappers.<SysUserIdentity>lambdaQuery()
                .eq(SysUserIdentity::getUserId, userId).eq(SysUserIdentity::getIdentityStatus, ACTIVE)
                .orderByAsc(SysUserIdentity::getCreatedAt));
    }

    public Optional<SysUserIdentity> findActive(Long userId, Long tenantId, IdentityType type) {
        return identityMapper.selectList(Wrappers.<SysUserIdentity>lambdaQuery()
                        .eq(SysUserIdentity::getUserId, userId).eq(SysUserIdentity::getTenantId, tenantId)
                        .eq(SysUserIdentity::getIdentityType, type.name())
                        .eq(SysUserIdentity::getIdentityStatus, ACTIVE))
                .stream().findFirst();
    }

    @Transactional
    public SysUserIdentity create(Long userId, Long tenantId, IdentityType type, String displayName,
                                  Long studentId, Collection<String> roleKeys) {
        SysUserIdentity identity = new SysUserIdentity();
        identity.setUserId(userId);
        identity.setTenantId(tenantId);
        identity.setIdentityType(type.name());
        identity.setDisplayName(displayName);
        identity.setStudentId(studentId);
        identity.setIdentityStatus(ACTIVE);
        identityMapper.insert(identity);
        roleService.grant(identity.getId(), tenantId, roleKeys);
        return identity;
    }

    /** 游客身份：自注册、离职后退回（8.3：账号保留，身份退回游客）。 */
    @Transactional
    public SysUserIdentity ensureVisitorIdentity(Long userId, String nickname) {
        return findActive(userId, Constants.VISITOR_TENANT_ID, IdentityType.VISITOR_TEACHER)
                .orElseGet(() -> create(userId, Constants.VISITOR_TENANT_ID, IdentityType.VISITOR_TEACHER,
                        "访客学校（默认）· 游客教师", null, List.of(RoleService.VISITOR_TEACHER)));
    }

    /**
     * 撤销身份：状态置为 REVOKED，持有该身份的登录会话立即下线，并广播事件让导出链接等失效。
     */
    @Transactional
    public void revoke(Long identityId, String reason) {
        SysUserIdentity identity = get(identityId);
        if (REVOKED.equals(identity.getIdentityStatus())) {
            return;
        }
        identity.setIdentityStatus(REVOKED);
        identity.setRevokedAt(LocalDateTime.now());
        identity.setRevokeReason(reason);
        identityMapper.updateById(identity);
        kickoutSessionsOf(identity.getUserId(), identityId);
        events.publishEvent(new IdentityRevokedEvent(identity.getUserId(), identityId, identity.getTenantId(), reason));
    }

    /** 权限变化（换班、角色调整）后，让该身份的会话重新加载上下文。 */
    public void refreshSessionsOf(Long userId, Long identityId) {
        for (String token : StpUtil.getTokenValueListByLoginId(userId)) {
            var session = StpUtil.getTokenSessionByToken(token);
            Object v = session == null ? null : session.get(LoginHelper.SESSION_IDENTITY);
            if (v instanceof CurrentIdentity ci && Objects.equals(ci.identityId(), identityId)) {
                session.set(LoginHelper.SESSION_IDENTITY, buildContext(get(identityId)));
            }
        }
    }

    private void kickoutSessionsOf(Long userId, Long identityId) {
        for (String token : StpUtil.getTokenValueListByLoginId(userId)) {
            var session = StpUtil.getTokenSessionByToken(token);
            Object v = session == null ? null : session.get(LoginHelper.SESSION_IDENTITY);
            if (v instanceof CurrentIdentity ci && Objects.equals(ci.identityId(), identityId)) {
                StpUtil.kickoutByTokenValue(token);
            }
        }
    }

    /** 根据身份组装上下文：权限码取所有角色的并集，数据范围取最大的那个。 */
    public CurrentIdentity buildContext(SysUserIdentity identity) {
        if (!ACTIVE.equals(identity.getIdentityStatus())) {
            throw new BizException(ErrorCode.IDENTITY_INVALID);
        }
        List<SysRole> roles = roleService.rolesOfIdentity(identity.getId());
        Set<String> keys = new HashSet<>();
        Set<String> perms = new HashSet<>();
        DataScope scope = null;
        for (SysRole r : roles) {
            keys.add(r.getRoleKey());
            if (r.getPerms() != null) {
                Arrays.stream(r.getPerms().split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(perms::add);
            }
            scope = DataScope.widest(scope, DataScope.valueOf(r.getDataScope()));
        }
        SysTenant tenant = tenantMapper.selectById(identity.getTenantId());
        Long region = tenant == null || tenant.getRegionCode() == null ? null : parseRegion(tenant.getRegionCode());
        return new CurrentIdentity(identity.getUserId(), identity.getId(), identity.getTenantId(),
                IdentityType.valueOf(identity.getIdentityType()), Set.copyOf(keys), Set.copyOf(perms),
                scope == null ? DataScope.SELF : scope, region);
    }

    private Long parseRegion(String code) {
        try {
            return Long.parseLong(code);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
