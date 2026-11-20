package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.api.system.IdentityRevokedEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.system.domain.SysDownloadToken;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.mapper.SysDownloadTokenMapper;
import com.jinbang.system.mapper.SysUserIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** 导出文件下载凭证：绑定生成者身份，身份撤销即失效（PRD 8.3、AC-14）。 */
@Service
@RequiredArgsConstructor
public class ExportLinkService implements ExportLinkApi {
    private final SysDownloadTokenMapper tokenMapper;
    private final SysUserIdentityMapper identityMapper;
    private final FileStorage storage;
    private final SecureRandom random = new SecureRandom();

    public record FileDownload(String fileName, byte[] content) {
    }

    @Override
    public String createLink(String storageKey, String fileName, Duration ttl) {
        CurrentIdentity ci = LoginHelper.require();
        byte[] b = new byte[24];
        random.nextBytes(b);
        SysDownloadToken t = new SysDownloadToken();
        t.setToken(HexFormat.of().formatHex(b));
        t.setUserId(ci.userId());
        t.setIdentityId(ci.identityId());
        t.setTenantId(ci.tenantId());
        t.setStorageKey(storageKey);
        t.setFileName(fileName);
        t.setExpireAt(LocalDateTime.now().plus(ttl));
        t.setRevoked(false);
        tokenMapper.insert(t);
        return "/api/files/download/" + t.getToken();
    }

    /**
     * 短时签名链接：链接里的随机令牌本身就是凭证（这样 &lt;img&gt;、&lt;a download&gt; 不带请求头也能用）。
     * 下载时再校验：未过期、未撤销、生成者的身份仍有效。身份被撤销（离职、解绑）时令牌同时作废（AC-14）。
     * 如果请求带了登录态，还要求是本人，防止把别人的链接贴进自己的会话里用。
     */
    public FileDownload download(String token) {
        SysDownloadToken t = tokenMapper.selectOne(Wrappers.<SysDownloadToken>lambdaQuery()
                .eq(SysDownloadToken::getToken, token));
        CurrentIdentity ci = LoginHelper.current().orElse(null);
        if (t == null || Boolean.TRUE.equals(t.getRevoked()) || t.getExpireAt().isBefore(LocalDateTime.now())
                || (ci != null && !t.getUserId().equals(ci.userId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "下载链接已失效");
        }
        SysUserIdentity identity = identityMapper.selectById(t.getIdentityId());
        if (identity == null || !IdentityService.ACTIVE.equals(identity.getIdentityStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "下载链接已失效");
        }
        return new FileDownload(t.getFileName(), storage.get(t.getStorageKey()));
    }

    @EventListener
    public void onRevoked(IdentityRevokedEvent e) {
        tokenMapper.update(Wrappers.<SysDownloadToken>lambdaUpdate().set(SysDownloadToken::getRevoked, true)
                .eq(SysDownloadToken::getIdentityId, e.identityId()).eq(SysDownloadToken::getRevoked, false));
    }
}
