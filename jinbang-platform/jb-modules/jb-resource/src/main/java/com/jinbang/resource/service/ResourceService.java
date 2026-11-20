package com.jinbang.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.account.AccountApi;
import com.jinbang.api.operation.AuditApi;
import com.jinbang.api.operation.ReportUpheldEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.statemachine.StateMachine;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.resource.domain.ResBasket;
import com.jinbang.resource.domain.ResCollectionActivity;
import com.jinbang.resource.domain.ResComment;
import com.jinbang.resource.domain.ResDownload;
import com.jinbang.resource.domain.ResFavorite;
import com.jinbang.resource.domain.ResResource;
import com.jinbang.resource.mapper.ResBasketMapper;
import com.jinbang.resource.mapper.ResCollectionActivityMapper;
import com.jinbang.resource.mapper.ResCommentMapper;
import com.jinbang.resource.mapper.ResDownloadMapper;
import com.jinbang.resource.mapper.ResFavoriteMapper;
import com.jinbang.resource.mapper.ResResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 资源中心（PRD 3.2、3.6、3.9、11.3）。
 * <pre>
 * 平台资源：草稿 → 待审核 → 已上架 ⇄ 已下架；待审核 → 驳回(原因) → 修改后重新提交
 * 征集投稿：已提交 → 审核中 → 通过(纳入平台资源 + 奖励) / 驳回
 * 校本资源：教师分享 → 学科组长 / 管理员审核 → 学校空间发布
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ResourceService implements com.jinbang.api.resource.ResourceApi {
    public enum Status { DRAFT, PENDING, ONLINE, OFFLINE, REJECTED }

    public enum Event { SUBMIT, APPROVE, REJECT, TAKE_DOWN, PUT_ON, EDIT }

    static final StateMachine<Status, Event> FSM = StateMachine.<Status, Event>builder("资源", Status.class)
            .on(Status.DRAFT, Event.SUBMIT, Status.PENDING)
            .on(Status.REJECTED, Event.SUBMIT, Status.PENDING)
            .on(Status.REJECTED, Event.EDIT, Status.DRAFT)
            .on(Status.PENDING, Event.APPROVE, Status.ONLINE)
            .on(Status.PENDING, Event.REJECT, Status.REJECTED)
            .on(Status.ONLINE, Event.TAKE_DOWN, Status.OFFLINE)
            .on(Status.OFFLINE, Event.PUT_ON, Status.ONLINE)
            .on(Status.OFFLINE, Event.EDIT, Status.DRAFT)
            .build();

    private static final Set<String> PAID_LEVELS = Set.of("PREMIUM", "EXCLUSIVE");

    private final ResResourceMapper resourceMapper;
    private final ResDownloadMapper downloadMapper;
    private final ResFavoriteMapper favoriteMapper;
    private final ResBasketMapper basketMapper;
    private final ResCommentMapper commentMapper;
    private final ResCollectionActivityMapper activityMapper;
    private final FileStorage storage;
    private final AccountApi accountApi;
    private final AuditApi auditApi;

    public record Query(String keyword, String scope, String period, String subject, String resType, String level,
                        Long catalogId, Integer year, String region, String sort, PageQuery page) {
    }

    public record UploadReq(String scope, String title, String resType, String level, Long priceCoins, String period,
                            String subject, Long catalogId, Integer year, String region, String summary,
                            String publishRange, String rangeValue, Long activityId) {
    }

    /** 资源列表 / 搜索中心：只返回当前身份可见的资源。 */
    public PageResult<ResResource> search(CurrentIdentity ci, Query q) {
        LambdaQueryWrapper<ResResource> w = Wrappers.lambdaQuery();
        String scope = q.scope() == null ? "PLATFORM" : q.scope();
        switch (scope) {
            case "SCHOOL" -> {
                if (!ci.type().isSchoolMember() && !ci.isPlatform()) {
                    throw new BizException(ErrorCode.FORBIDDEN);
                }
                w.eq(ResResource::getResScope, "SCHOOL").eq(ResResource::getTenantId, ci.tenantId())
                        .eq(ResResource::getResStatus, "ONLINE");
            }
            case "MINE" -> w.eq(ResResource::getAuthorUserId, ci.userId());
            default -> w.eq(ResResource::getResScope, "PLATFORM").eq(ResResource::getResStatus, "ONLINE");
        }
        w.like(notBlank(q.keyword()), ResResource::getTitle, q.keyword())
                .eq(notBlank(q.period()), ResResource::getPeriod, q.period())
                .eq(notBlank(q.subject()), ResResource::getSubject, q.subject())
                .eq(notBlank(q.resType()), ResResource::getResType, q.resType())
                .eq(notBlank(q.level()), ResResource::getResLevel, q.level())
                .eq(q.catalogId() != null, ResResource::getCatalogId, q.catalogId())
                .eq(q.year() != null, ResResource::getResYear, q.year())
                .eq(notBlank(q.region()), ResResource::getRegionCode, q.region());
        switch (q.sort() == null ? "" : q.sort()) {
            case "download" -> w.orderByDesc(ResResource::getDownloadCount);
            case "view" -> w.orderByDesc(ResResource::getViewCount);
            default -> w.orderByDesc(ResResource::getPublishedAt).orderByDesc(ResResource::getCreatedAt);
        }
        return PageResult.of(resourceMapper.selectPage((q.page() == null ? new PageQuery() : q.page()).toPage(), w));
    }

    public ResResource detail(CurrentIdentity ci, Long id) {
        ResResource r = visible(ci, id);
        resourceMapper.update(Wrappers.<ResResource>lambdaUpdate().setSql("view_count = view_count + 1")
                .eq(ResResource::getId, id));
        return r;
    }

    private ResResource visible(CurrentIdentity ci, Long id) {
        ResResource r = BizException.notNull(resourceMapper.selectById(id), "资源");
        boolean mine = r.getAuthorUserId().equals(ci.userId());
        boolean ok = mine || ci.isPlatform() || ("ONLINE".equals(r.getResStatus()) && ("PLATFORM".equals(r.getResScope())
                || ("SCHOOL".equals(r.getResScope()) && Objects.equals(r.getTenantId(), ci.tenantId()))));
        if (!ok) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在或已下架");
        }
        return r;
    }

    @Transactional
    public ResResource upload(CurrentIdentity ci, UploadReq req, String fileName, byte[] bytes) {
        BizException.throwIf(req.title() == null || req.title().isBlank(), ErrorCode.BAD_REQUEST, "请填写资源标题");
        String scope = req.scope() == null ? "PERSONAL" : req.scope();
        if ("SCHOOL".equals(scope) && !ci.type().isSchoolMember()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if ("PLATFORM".equals(scope) && req.activityId() == null && !ci.hasPerm("op:resource")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        String ext = fileName != null && fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        ResResource r = new ResResource();
        r.setResScope(scope);
        r.setTenantId("SCHOOL".equals(scope) ? ci.tenantId() : null);
        r.setAuthorUserId(ci.userId());
        r.setTitle(req.title());
        r.setResType(req.resType() == null ? "OTHER" : req.resType());
        r.setResLevel(req.level() == null ? "FREE" : req.level());
        r.setPriceCoins(PAID_LEVELS.contains(r.getResLevel()) ? Math.max(1, req.priceCoins() == null ? 10 : req.priceCoins()) : 0L);
        r.setPeriod(req.period());
        r.setSubject(req.subject());
        r.setCatalogId(req.catalogId());
        r.setResYear(req.year());
        r.setRegionCode(req.region());
        r.setSummary(req.summary());
        r.setPublishRange(req.publishRange());
        r.setRangeValue(req.rangeValue());
        r.setActivityId(req.activityId());
        if (bytes != null && bytes.length > 0) {
            r.setFileKey(storage.put("resource", ext, bytes));
            r.setFileExt(ext);
            r.setFileSize((long) bytes.length);
        }
        r.setResStatus(Status.DRAFT.name());
        r.setDownloadCount(0L);
        r.setViewCount(0L);
        resourceMapper.insert(r);
        if (req.activityId() != null) {
            ResCollectionActivity a = BizException.notNull(activityMapper.selectById(req.activityId()), "征集活动");
            BizException.throwIf(!"ONGOING".equals(a.getActivityStatus()) || (a.getEndAt() != null
                    && a.getEndAt().isBefore(LocalDateTime.now())), ErrorCode.STATE_TRANSITION_DENIED, "该征集活动已结束");
            submit(ci, r.getId());
        }
        return r;
    }

    /** 提交审核：平台 / 征集进平台审核，校本进本校审核；个人资源不需要审核，直接可用。 */
    @Transactional
    public ResResource submit(CurrentIdentity ci, Long id) {
        ResResource r = own(ci, id);
        if ("PERSONAL".equals(r.getResScope())) {
            r.setResStatus(Status.ONLINE.name());
            r.setPublishedAt(LocalDateTime.now());
            resourceMapper.updateById(r);
            return r;
        }
        transit(r, Event.SUBMIT);
        String bizType = r.getActivityId() != null ? "COLLECTION" : "SCHOOL".equals(r.getResScope()) ? "SCHOOL_RESOURCE" : "RESOURCE";
        auditApi.submit(bizType, r.getId(), r.getTenantId(), r.getTitle(), r.getSummary(), r.getAuthorUserId());
        return r;
    }

    @EventListener
    public void onAudit(AuditApi.AuditDecidedEvent e) {
        if (!Set.of("RESOURCE", "SCHOOL_RESOURCE", "COLLECTION").contains(e.bizType())) {
            return;
        }
        ResResource r = resourceMapper.selectById(e.bizId());
        if (r == null || !Status.PENDING.name().equals(r.getResStatus())) {
            return;
        }
        if (e.approved()) {
            transit(r, Event.APPROVE);
            r.setPublishedAt(LocalDateTime.now());
            resourceMapper.updateById(r);
            if ("COLLECTION".equals(e.bizType())) {
                ResCollectionActivity a = activityMapper.selectById(r.getActivityId());
                if (a != null && a.getRewardCoins() > 0) {
                    accountApi.grantCoins(r.getAuthorUserId(), a.getRewardCoins(), "TASK", "COLLECTION:" + r.getId(),
                            "资源征集投稿通过：" + r.getTitle());
                }
                if (a != null && a.getRewardExp() > 0) {
                    accountApi.addExp(r.getAuthorUserId(), a.getRewardExp(), "COLLECTION", String.valueOf(r.getId()),
                            "资源征集投稿通过");
                }
            }
        } else {
            r.setRejectReason(e.reason());
            transit(r, Event.REJECT);
            resourceMapper.updateById(r);
        }
    }

    /** 举报成立：资源下架（PRD 11.7）。 */
    @EventListener
    public void onReport(ReportUpheldEvent e) {
        if (!"RESOURCE".equals(e.targetType())) {
            return;
        }
        ResResource r = resourceMapper.selectById(e.targetId());
        if (r != null && Status.ONLINE.name().equals(r.getResStatus())) {
            transit(r, Event.TAKE_DOWN);
        }
    }

    @Transactional
    @OperLog(module = "资源", action = "上下架")
    public ResResource changeShelf(CurrentIdentity ci, Long id, boolean online) {
        ResResource r = BizException.notNull(resourceMapper.selectById(id), "资源");
        boolean mineSchool = "SCHOOL".equals(r.getResScope()) && Objects.equals(r.getTenantId(), ci.tenantId())
                && (r.getAuthorUserId().equals(ci.userId()) || ci.hasPerm("school:resource:audit"));
        if (!ci.hasPerm("op:resource") && !mineSchool) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        transit(r, online ? Event.PUT_ON : Event.TAKE_DOWN);
        return r;
    }

    public record DownloadResult(String fileKey, String fileName, long paidCoins) {
    }

    /**
     * 下载（PRD 3.2 付费校验 checkPay）：免费直接下；精品 / 特供首次下载扣金币，之后永久可下，不重复扣费。
     */
    @Transactional
    public DownloadResult download(CurrentIdentity ci, Long id) {
        ResResource r = visible(ci, id);
        BizException.throwIf(r.getFileKey() == null, ErrorCode.NOT_FOUND, "该资源没有可下载的文件");
        ResDownload d = downloadMapper.selectOne(Wrappers.<ResDownload>lambdaQuery().eq(ResDownload::getUserId, ci.userId())
                .eq(ResDownload::getResourceId, id));
        long paid = 0;
        if (d == null) {
            boolean needPay = PAID_LEVELS.contains(r.getResLevel()) && !r.getAuthorUserId().equals(ci.userId())
                    && !"SCHOOL".equals(r.getResScope());
            if (needPay) {
                accountApi.spendCoins(ci.userId(), r.getPriceCoins(), "RESOURCE", ci.userId() + ":" + id, "下载资源：" + r.getTitle());
                paid = r.getPriceCoins();
            }
            d = new ResDownload();
            d.setUserId(ci.userId());
            d.setResourceId(id);
            d.setPaidCoins(paid);
            d.setTimes(1);
            try {
                downloadMapper.insert(d);
            } catch (DuplicateKeyException ignored) {
                // 并发重复点击：权益已存在
            }
        } else {
            downloadMapper.update(Wrappers.<ResDownload>lambdaUpdate().setSql("times = times + 1").eq(ResDownload::getId, d.getId()));
        }
        resourceMapper.update(Wrappers.<ResResource>lambdaUpdate().setSql("download_count = download_count + 1")
                .eq(ResResource::getId, id));
        return new DownloadResult(r.getFileKey(), r.getTitle() + (r.getFileExt() == null ? "" : "." + r.getFileExt()), paid);
    }

    public PageResult<ResResource> myDownloads(CurrentIdentity ci, PageQuery q) {
        List<Long> ids = downloadMapper.selectList(Wrappers.<ResDownload>lambdaQuery().eq(ResDownload::getUserId, ci.userId())
                .orderByDesc(ResDownload::getUpdatedAt)).stream().map(ResDownload::getResourceId).toList();
        if (ids.isEmpty()) {
            return new PageResult<>(0, List.of());
        }
        return PageResult.of(resourceMapper.selectPage(q.toPage(), Wrappers.<ResResource>lambdaQuery().in(ResResource::getId, ids)));
    }

    // ---------------- 收藏 / 资源篮 / 评论 ----------------

    public void favorite(CurrentIdentity ci, String targetType, Long targetId, String folder, boolean on) {
        if (on) {
            ResFavorite f = new ResFavorite();
            f.setUserId(ci.userId());
            f.setTargetType(targetType);
            f.setTargetId(targetId);
            f.setFolder(folder == null ? "默认收藏夹" : folder);
            try {
                favoriteMapper.insert(f);
            } catch (DuplicateKeyException ignored) {
                // 已收藏
            }
        } else {
            favoriteMapper.delete(Wrappers.<ResFavorite>lambdaQuery().eq(ResFavorite::getUserId, ci.userId())
                    .eq(ResFavorite::getTargetType, targetType).eq(ResFavorite::getTargetId, targetId));
        }
    }

    public List<ResFavorite> favorites(CurrentIdentity ci, String targetType) {
        return favoriteMapper.selectList(Wrappers.<ResFavorite>lambdaQuery().eq(ResFavorite::getUserId, ci.userId())
                .eq(targetType != null, ResFavorite::getTargetType, targetType).orderByDesc(ResFavorite::getCreatedAt));
    }

    public void basketAdd(CurrentIdentity ci, String itemType, List<Long> ids) {
        long count = basketMapper.selectCount(Wrappers.<ResBasket>lambdaQuery().eq(ResBasket::getUserId, ci.userId())
                .eq(ResBasket::getItemType, itemType));
        for (Long id : ids) {
            ResBasket b = new ResBasket();
            b.setUserId(ci.userId());
            b.setItemType(itemType);
            b.setItemId(id);
            b.setSortNo((int) ++count);
            try {
                basketMapper.insert(b);
            } catch (DuplicateKeyException ignored) {
                // 已在篮中
            }
        }
    }

    public void basketRemove(CurrentIdentity ci, String itemType, List<Long> ids) {
        basketMapper.delete(Wrappers.<ResBasket>lambdaQuery().eq(ResBasket::getUserId, ci.userId())
                .eq(ResBasket::getItemType, itemType).in(ids != null && !ids.isEmpty(), ResBasket::getItemId, ids));
    }

    public List<ResBasket> basket(CurrentIdentity ci, String itemType) {
        return basketMapper.selectList(Wrappers.<ResBasket>lambdaQuery().eq(ResBasket::getUserId, ci.userId())
                .eq(ResBasket::getItemType, itemType).orderByAsc(ResBasket::getSortNo));
    }

    public ResComment comment(CurrentIdentity ci, Long resourceId, String type, String content) {
        visible(ci, resourceId);
        BizException.throwIf(!Set.of("COMMENT", "COMPLAIN", "ERROR").contains(type) || content == null || content.isBlank(),
                ErrorCode.BAD_REQUEST);
        ResComment c = new ResComment();
        c.setResourceId(resourceId);
        c.setUserId(ci.userId());
        c.setCommentType(type);
        c.setContent(content);
        c.setCommentStatus("COMMENT".equals(type) ? "VISIBLE" : "PENDING");
        commentMapper.insert(c);
        return c;
    }

    public List<ResComment> comments(Long resourceId) {
        return commentMapper.selectList(Wrappers.<ResComment>lambdaQuery().eq(ResComment::getResourceId, resourceId)
                .eq(ResComment::getCommentType, "COMMENT").eq(ResComment::getCommentStatus, "VISIBLE")
                .orderByDesc(ResComment::getCreatedAt));
    }

    // ---------------- 征集活动 ----------------

    public List<ResCollectionActivity> activities() {
        return activityMapper.selectList(Wrappers.<ResCollectionActivity>lambdaQuery()
                .orderByDesc(ResCollectionActivity::getCreatedAt));
    }

    public ResCollectionActivity saveActivity(ResCollectionActivity a) {
        if (a.getActivityStatus() == null) {
            a.setActivityStatus("ONGOING");
        }
        if (a.getRewardCoins() == null) {
            a.setRewardCoins(0L);
        }
        if (a.getRewardExp() == null) {
            a.setRewardExp(0L);
        }
        if (a.getId() == null) {
            activityMapper.insert(a);
        } else {
            activityMapper.updateById(a);
        }
        return a;
    }

    /** 我的投稿（PRD 3.6 ➕P0）：审核状态、驳回原因。 */
    public List<ResResource> mySubmissions(CurrentIdentity ci) {
        return resourceMapper.selectList(Wrappers.<ResResource>lambdaQuery().eq(ResResource::getAuthorUserId, ci.userId())
                .isNotNull(ResResource::getActivityId).orderByDesc(ResResource::getCreatedAt));
    }

    private ResResource own(CurrentIdentity ci, Long id) {
        ResResource r = BizException.notNull(resourceMapper.selectById(id), "资源");
        if (!r.getAuthorUserId().equals(ci.userId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return r;
    }

    private void transit(ResResource r, Event e) {
        Status next = FSM.fire(Status.valueOf(r.getResStatus()), e);
        int changed = resourceMapper.update(Wrappers.<ResResource>lambdaUpdate().set(ResResource::getResStatus, next.name())
                .set(next == Status.ONLINE, ResResource::getPublishedAt, LocalDateTime.now())
                .set(e == Event.REJECT, ResResource::getRejectReason, r.getRejectReason())
                .eq(ResResource::getId, r.getId()).eq(ResResource::getResStatus, r.getResStatus()));
        BizException.throwIf(changed == 0, ErrorCode.CONFLICT);
        r.setResStatus(next.name());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    @Transactional
    public ResourceFile takeForPrep(Long resourceId) {
        CurrentIdentity ci = com.jinbang.common.security.LoginHelper.require();
        DownloadResult d = download(ci, resourceId);
        ResResource r = resourceMapper.selectById(resourceId);
        return new ResourceFile(resourceId, r.getTitle(), r.getFileExt(), d.fileKey(), r.getFileSize(), d.paidCoins());
    }
}
