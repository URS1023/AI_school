package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.IdentityType;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 平台运营开通学校（手册：金榜技术人员创建管理员账号）。 */
@Service
@RequiredArgsConstructor
public class TenantAdminService {
    private final SysTenantMapper tenantMapper;
    private final UserService userService;
    private final IdentityService identityService;

    public record CreateSchoolReq(String name, String regionCode, String periods, String adminName, String adminPhone,
                                  String packageCode, LocalDateTime expireAt) {
    }

    public List<SysTenant> list() {
        return tenantMapper.selectList(Wrappers.<SysTenant>lambdaQuery().gt(SysTenant::getId, 1L)
                .orderByDesc(SysTenant::getCreatedAt));
    }

    @Transactional
    @OperLog(module = "运营-学校", action = "开通学校")
    public SysTenant createSchool(CreateSchoolReq req) {
        BizException.throwIf(req.name() == null || req.name().isBlank(), ErrorCode.BAD_REQUEST, "请填写学校名称");
        SysTenant t = new SysTenant();
        t.setTenantName(req.name());
        t.setRegionCode(req.regionCode());
        t.setPeriods(req.periods());
        t.setPackageCode(req.packageCode());
        t.setExpireAt(req.expireAt());
        t.setInviteCode(SchoolOrgService.newInviteCode() + SchoolOrgService.newInviteCode().substring(0, 2));
        t.setTenantStatus("ACTIVE");
        tenantMapper.insert(t);
        if (req.adminPhone() != null) {
            SysUser admin = userService.findOrCreateByPhone(req.adminPhone(), req.adminName());
            identityService.create(admin.getId(), t.getId(), IdentityType.SCHOOL_ADMIN,
                    t.getTenantName() + " · 管理员", null, List.of(RoleService.SCHOOL_ADMIN));
        }
        return t;
    }

    @Transactional
    public SysTenant update(Long id, String status, String packageCode, LocalDateTime expireAt) {
        SysTenant t = BizException.notNull(tenantMapper.selectById(id), "学校");
        if (status != null) {
            t.setTenantStatus(status);
        }
        if (packageCode != null) {
            t.setPackageCode(packageCode);
        }
        if (expireAt != null) {
            t.setExpireAt(expireAt);
        }
        tenantMapper.updateById(t);
        return t;
    }
}
