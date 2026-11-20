package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.Constants;
import com.jinbang.common.log.OperLogEvent;
import com.jinbang.common.security.IdentityType;
import com.jinbang.system.domain.SysOperLog;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.SysOperLogMapper;
import com.jinbang.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/** 启动时确保有平台超级管理员；并把操作日志事件落库。 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SystemBootstrap implements ApplicationRunner {
    private final SysUserMapper userMapper;
    private final SysOperLogMapper operLogMapper;
    private final UserService userService;
    private final IdentityService identityService;

    @Value("${jb.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "admin"))) {
            return;
        }
        String pwd = adminPassword;
        if (pwd == null || pwd.isBlank()) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            pwd = "Jb" + HexFormat.of().formatHex(b);
            log.warn("已创建平台超级管理员 admin，初始密码：{}（首次登录必须修改；可用 jb.admin.password 指定）", pwd);
        }
        SysUser admin = userService.create(null, "admin", "超级管理员", pwd, true);
        identityService.create(admin.getId(), Constants.PLATFORM_TENANT_ID, IdentityType.PLATFORM, "平台运营 · 超级管理员",
                null, List.of(RoleService.PLATFORM_ADMIN));
    }

    @EventListener
    public void onOperLog(OperLogEvent e) {
        SysOperLog l = new SysOperLog();
        l.setTenantId(e.tenantId());
        l.setUserId(e.userId());
        l.setIdentityId(e.identityId());
        l.setModule(e.module());
        l.setAction(e.action());
        l.setMethod(e.method());
        l.setArgs(e.args());
        l.setSuccess(e.success());
        l.setErrorMsg(e.error() == null ? null : e.error().substring(0, Math.min(500, e.error().length())));
        l.setCostMs(e.costMs());
        operLogMapper.insert(l);
    }
}
