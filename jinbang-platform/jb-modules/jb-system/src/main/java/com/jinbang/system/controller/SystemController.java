package com.jinbang.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.util.Masks;
import com.jinbang.system.domain.SysDictData;
import com.jinbang.system.domain.SysOperLog;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.SysOperLogMapper;
import com.jinbang.system.service.ExportLinkService;
import com.jinbang.system.service.MenuDictService;
import com.jinbang.system.service.TenantAdminService;
import com.jinbang.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class SystemController {
    private final UserService userService;
    private final MenuDictService menuDictService;
    private final ExportLinkService exportLinkService;
    private final TenantAdminService tenantAdminService;
    private final SysOperLogMapper operLogMapper;
    private final com.jinbang.system.service.DirectoryService directoryService;

    public record MeVO(Long userId, String nickname, String phone, String avatar, CurrentIdentity identity) {
    }

    public record ProfileReq(String nickname, String avatar) {
    }

    @GetMapping("/api/system/user/me")
    public R<MeVO> me() {
        CurrentIdentity ci = LoginHelper.require();
        SysUser u = userService.get(ci.userId());
        return R.ok(new MeVO(u.getId(), u.getNickname(), Masks.phone(u.getPhone()), u.getAvatar(), ci));
    }

    @PutMapping("/api/system/user/profile")
    public R<Void> profile(@RequestBody ProfileReq req) {
        SysUser u = userService.get(LoginHelper.userId());
        if (req.nickname() != null && !req.nickname().isBlank()) {
            u.setNickname(req.nickname().trim());
        }
        if (req.avatar() != null) {
            u.setAvatar(req.avatar());
        }
        userService.update(u);
        return R.ok();
    }

    /** 学生端 / 家长端「我是谁」。 */
    @GetMapping("/api/portal/me")
    public R<com.jinbang.system.service.DirectoryService.PortalMe> portalMe() {
        return R.ok(directoryService.portalMe(LoginHelper.require()));
    }

    @GetMapping("/api/system/menu/routers")
    public R<List<MenuDictService.MenuVO>> routers() {
        return R.ok(menuDictService.routers(LoginHelper.require()));
    }

    @GetMapping("/api/open/dict/{dictType}")
    public R<List<SysDictData>> dict(@PathVariable String dictType) {
        return R.ok(menuDictService.dict(dictType));
    }

    @SaCheckPermission("op:dict")
    @PostMapping("/api/admin/dict")
    public R<SysDictData> saveDict(@RequestBody SysDictData data) {
        return R.ok(menuDictService.saveDict(data));
    }

    @GetMapping("/api/files/download/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        ExportLinkService.FileDownload d = exportLinkService.download(token);
        String name = d.fileName() == null ? "file" : d.fileName().toLowerCase();
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : name.endsWith(".pdf") ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM;
        // 图片和 PDF 在页面里直接显示，其余作为附件下载
        ContentDisposition cd = (type == MediaType.APPLICATION_OCTET_STREAM ? ContentDisposition.attachment()
                : ContentDisposition.inline()).filename(d.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type).body(d.content());
    }

    // ---------------- 平台运营：学校开通 ----------------

    @SaCheckPermission("op:tenant")
    @GetMapping("/api/admin/tenants")
    public R<List<SysTenant>> tenants() {
        return R.ok(tenantAdminService.list());
    }

    @SaCheckPermission("op:tenant")
    @PostMapping("/api/admin/tenants")
    public R<SysTenant> createTenant(@RequestBody TenantAdminService.CreateSchoolReq req) {
        return R.ok(tenantAdminService.createSchool(req));
    }

    public record TenantUpdateReq(String status, String packageCode, LocalDateTime expireAt) {
    }

    @SaCheckPermission("op:tenant")
    @PutMapping("/api/admin/tenants/{id}")
    public R<SysTenant> updateTenant(@PathVariable Long id, @RequestBody TenantUpdateReq req) {
        return R.ok(tenantAdminService.update(id, req.status(), req.packageCode(), req.expireAt()));
    }

    /** 操作日志：学校管理员看本校，平台看全部。 */
    @SaCheckPermission(value = {"school:org:manage", "op:user"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    @GetMapping("/api/system/oper-logs")
    public R<PageResult<SysOperLog>> operLogs(PageQuery q, @RequestParam(required = false) String module) {
        CurrentIdentity ci = LoginHelper.require();
        var w = Wrappers.<SysOperLog>lambdaQuery().eq(module != null, SysOperLog::getModule, module)
                .orderByDesc(SysOperLog::getCreatedAt);
        if (!ci.isPlatform()) {
            w.eq(SysOperLog::getTenantId, ci.tenantId());
        }
        return R.ok(PageResult.of(operLogMapper.selectPage(q.toPage(), w)));
    }
}
