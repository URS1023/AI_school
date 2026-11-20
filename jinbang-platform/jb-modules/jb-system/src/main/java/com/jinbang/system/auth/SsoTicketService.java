package com.jinbang.system.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 主站 → AI 平台单点登录 ticket（PRD 2.1、13）：一次性、默认 60 秒失效。
 * 取用即删除（等价 Redis GETDEL），同一 ticket 第二次使用必然失败，防止从地址栏/历史/Referer 泄露后被重放。
 * 拆分部署后换成 Redis 实现，接口不变。
 */
@Service
public class SsoTicketService {
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, TicketPayload> tickets;

    public record TicketPayload(Long userId, Long identityId) {
    }

    public SsoTicketService(@Value("${jb.sso.ticket-ttl-seconds:60}") long ttlSeconds) {
        this.tickets = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(500_000).build();
    }

    public String issue(Long userId, Long identityId) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String ticket = HexFormat.of().formatHex(b);
        tickets.put(ticket, new TicketPayload(userId, identityId));
        return ticket;
    }

    public TicketPayload consume(String ticket) {
        TicketPayload p = ticket == null ? null : tickets.asMap().remove(ticket);
        if (p == null) {
            throw new BizException(ErrorCode.TICKET_INVALID);
        }
        return p;
    }
}
