package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.system.domain.SysIdentityRole;
import com.jinbang.system.domain.SysRole;
import com.jinbang.system.mapper.SysIdentityRoleMapper;
import com.jinbang.system.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleService {
    public static final String VISITOR_TEACHER = "visitor_teacher";
    public static final String TEACHER = "teacher";
    public static final String HEAD_TEACHER = "head_teacher";
    public static final String GRADE_LEADER = "grade_leader";
    public static final String SUBJECT_LEADER = "subject_leader";
    public static final String SCHOOL_LEADER = "school_leader";
    public static final String SCHOOL_ADMIN = "school_admin";
    public static final String STUDENT = "student";
    public static final String PARENT = "parent";
    public static final String PLATFORM_ADMIN = "platform_admin";

    private final SysRoleMapper roleMapper;
    private final SysIdentityRoleMapper identityRoleMapper;

    /** 优先取学校自定义的同名角色，没有就用平台模板。 */
    public SysRole byKey(String roleKey, Long tenantId) {
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRoleKey, roleKey));
        return roles.stream().filter(r -> tenantId != null && tenantId.equals(r.getTenantId())).findFirst()
                .or(() -> roles.stream().filter(r -> r.getTenantId() == null).findFirst())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "角色不存在：" + roleKey));
    }

    public List<SysRole> rolesOfIdentity(Long identityId) {
        List<Long> roleIds = identityRoleMapper.selectList(Wrappers.<SysIdentityRole>lambdaQuery()
                .eq(SysIdentityRole::getIdentityId, identityId)).stream().map(SysIdentityRole::getRoleId).toList();
        return roleIds.isEmpty() ? List.of() : roleMapper.selectByIds(roleIds);
    }

    public void grant(Long identityId, Long tenantId, Collection<String> roleKeys) {
        List<Long> existing = identityRoleMapper.selectList(Wrappers.<SysIdentityRole>lambdaQuery()
                .eq(SysIdentityRole::getIdentityId, identityId)).stream().map(SysIdentityRole::getRoleId).toList();
        for (String key : roleKeys) {
            SysRole role = byKey(key, tenantId);
            if (!existing.contains(role.getId())) {
                SysIdentityRole ir = new SysIdentityRole();
                ir.setIdentityId(identityId);
                ir.setRoleId(role.getId());
                identityRoleMapper.insert(ir);
            }
        }
    }

    public void revoke(Long identityId, Long tenantId, String roleKey) {
        SysRole role = byKey(roleKey, tenantId);
        identityRoleMapper.delete(Wrappers.<SysIdentityRole>lambdaQuery()
                .eq(SysIdentityRole::getIdentityId, identityId).eq(SysIdentityRole::getRoleId, role.getId()));
    }

    public List<SysRole> listAssignable(Long tenantId) {
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .and(w -> w.isNull(SysRole::getTenantId).or().eq(SysRole::getTenantId, tenantId))
                .orderByAsc(SysRole::getSortNo));
    }
}
