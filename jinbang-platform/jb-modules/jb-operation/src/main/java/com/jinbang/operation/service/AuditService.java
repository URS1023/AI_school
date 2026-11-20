package com.jinbang.operation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.AuditApi;
import com.jinbang.api.operation.ContentSafetyApi;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.operation.domain.OpAudit;
import com.jinbang.operation.mapper.OpAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统一审核中心（PRD 10.2.3）：先机审（敏感词）打风险标记，再人审。
 * 校本资源由本校学科组长 / 管理员审（11.3），其他由平台审核员审。
 */
@Service
@RequiredArgsConstructor
public class AuditService implements AuditApi {
    static final Map<String, String> BIZ_NAMES = Map.of("RESOURCE", "资源上架", "COLLECTION", "资源征集投稿",
            "POST", "帖子", "COMMENT", "评论", "SCHOOL_RESOURCE", "校本资源", "SHOP", "开店申请");

    private final OpAuditMapper auditMapper;
    private final ContentSafetyApi safety;
    private final NotifyApi notifyApi;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public Long submit(String bizType, Long bizId, Long tenantId, String title, String content, Long submitterUserId) {
        // 同一业务对象重复提交时，作废之前未处理的审核单
        auditMapper.update(Wrappers.<OpAudit>lambdaUpdate().set(OpAudit::getAuditStatus, "SUPERSEDED")
                .eq(OpAudit::getBizType, bizType).eq(OpAudit::getBizId, bizId).eq(OpAudit::getAuditStatus, "PENDING"));
        ContentSafetyApi.SafetyResult r = safety.check((title == null ? "" : title) + " " + (content == null ? "" : content));
        OpAudit a = new OpAudit();
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setTenantId(tenantId);
        a.setTitle(title);
        a.setContent(content);
        a.setSubmitterUserId(submitterUserId);
        a.setMachineResult(r.blocked() ? "RISK" : "PASS");
        a.setMachineHits(String.join(",", r.hits()));
        a.setAuditStatus("PENDING");
        auditMapper.insert(a);
        return a.getId();
    }

    public PageResult<OpAudit> pending(String bizType, PageQuery q) {
        CurrentIdentity ci = LoginHelper.require();
        var w = Wrappers.<OpAudit>lambdaQuery().eq(OpAudit::getAuditStatus, "PENDING")
                .eq(bizType != null, OpAudit::getBizType, bizType)
                .orderByDesc(OpAudit::getMachineResult).orderByAsc(OpAudit::getCreatedAt);
        if (!ci.isPlatform()) {
            // 学校侧只能审本校的校本资源
            w.eq(OpAudit::getTenantId, ci.tenantId()).eq(OpAudit::getBizType, "SCHOOL_RESOURCE");
        }
        return PageResult.of(auditMapper.selectPage(q.toPage(), w));
    }

    @Transactional
    @OperLog(module = "运营-审核", action = "审核")
    public void decide(Long auditId, boolean approve, String reason) {
        OpAudit a = BizException.notNull(auditMapper.selectById(auditId), "审核单");
        CurrentIdentity ci = LoginHelper.require();
        if (!ci.isPlatform() && !("SCHOOL_RESOURCE".equals(a.getBizType()) && a.getTenantId().equals(ci.tenantId()))) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        BizException.throwIf(!"PENDING".equals(a.getAuditStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该审核单已处理");
        BizException.throwIf(!approve && (reason == null || reason.isBlank()), ErrorCode.BAD_REQUEST, "驳回必须填写原因");
        int changed = auditMapper.update(Wrappers.<OpAudit>lambdaUpdate()
                .set(OpAudit::getAuditStatus, approve ? "APPROVED" : "REJECTED").set(OpAudit::getReason, reason)
                .set(OpAudit::getAuditorId, ci.userId()).set(OpAudit::getAuditedAt, LocalDateTime.now())
                .eq(OpAudit::getId, auditId).eq(OpAudit::getAuditStatus, "PENDING"));
        BizException.throwIf(changed == 0, ErrorCode.CONFLICT);
        events.publishEvent(new AuditDecidedEvent(a.getId(), a.getBizType(), a.getBizId(), approve, reason,
                a.getSubmitterUserId()));
        String name = BIZ_NAMES.getOrDefault(a.getBizType(), "内容");
        notifyApi.send(List.of(a.getSubmitterUserId()), "AUDIT", name + (approve ? "审核通过" : "审核未通过"),
                "「" + a.getTitle() + "」" + (approve ? "已通过审核" : "未通过审核，原因：" + reason),
                a.getBizType().toLowerCase() + ":" + a.getBizId());
    }
}
