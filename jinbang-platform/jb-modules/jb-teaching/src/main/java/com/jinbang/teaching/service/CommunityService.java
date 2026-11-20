package com.jinbang.teaching.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.AuditApi;
import com.jinbang.api.operation.ContentSafetyApi;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.api.operation.ReportUpheldEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.IdentityType;
import com.jinbang.teaching.domain.TcPost;
import com.jinbang.teaching.domain.TcPostComment;
import com.jinbang.teaching.domain.TcPostLike;
import com.jinbang.teaching.mapper.TcPostCommentMapper;
import com.jinbang.teaching.mapper.TcPostLikeMapper;
import com.jinbang.teaching.mapper.TcPostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 社区（PRD 4.4 金榜杏坛、3.10 学海共渡）：发布前敏感词审核（➕P0）；
 * 学生论坛面向未成年人，所有帖子人工审核后才可见，且按学校隔离；评论、点赞、举报、被互动通知。
 */
@Service
@RequiredArgsConstructor
public class CommunityService {
    public static final String XINGTAN = "XINGTAN";
    public static final String STUDENT_FORUM = "STUDENT_FORUM";

    private final TcPostMapper postMapper;
    private final TcPostCommentMapper commentMapper;
    private final TcPostLikeMapper likeMapper;
    private final ContentSafetyApi safety;
    private final AuditApi auditApi;
    private final NotifyApi notifyApi;

    public record PostReq(String board, String title, String content, String images, String attachmentKey, String period,
                          String subject) {
    }

    @Transactional
    public TcPost publish(CurrentIdentity ci, PostReq req, String authorName) {
        String board = req.board() == null ? XINGTAN : req.board();
        if (XINGTAN.equals(board)) {
            BizException.throwIf(!ci.hasPerm("community:use"), ErrorCode.FORBIDDEN);
        } else {
            BizException.throwIf(!ci.hasPerm("forum:post") && !ci.hasPerm("forum:manage"), ErrorCode.FORBIDDEN);
        }
        BizException.throwIf(req.content() == null || req.content().isBlank(), ErrorCode.BAD_REQUEST, "请输入内容");
        BizException.throwIf(XINGTAN.equals(board) && req.content().length() > 200, ErrorCode.BAD_REQUEST, "动态内容不超过 200 字");
        if (safety.check((req.title() == null ? "" : req.title()) + req.content()).blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        TcPost p = new TcPost();
        p.setBoard(board);
        p.setSchoolId(STUDENT_FORUM.equals(board) ? ci.tenantId() : null);
        p.setAuthorUserId(ci.userId());
        p.setAuthorName(authorName);
        p.setTitle(req.title());
        p.setContent(req.content());
        p.setImages(req.images());
        p.setAttachmentKey(req.attachmentKey());
        p.setPeriod(req.period());
        p.setSubject(req.subject());
        p.setLikeCount(0);
        p.setCommentCount(0);
        boolean manual = STUDENT_FORUM.equals(board) && ci.type() == IdentityType.STUDENT;
        p.setPostStatus(manual ? "PENDING" : "PUBLISHED");
        postMapper.insert(p);
        if (manual) {
            auditApi.submit("POST", p.getId(), ci.tenantId(), p.getTitle() == null ? "学生帖子" : p.getTitle(), p.getContent(), ci.userId());
        }
        return p;
    }

    @EventListener
    public void onAudit(AuditApi.AuditDecidedEvent e) {
        if (!"POST".equals(e.bizType())) {
            return;
        }
        TcPost p = postMapper.selectById(e.bizId());
        if (p != null && "PENDING".equals(p.getPostStatus())) {
            p.setPostStatus(e.approved() ? "PUBLISHED" : "REJECTED");
            p.setRejectReason(e.approved() ? null : e.reason());
            postMapper.updateById(p);
        }
    }

    @EventListener
    public void onReport(ReportUpheldEvent e) {
        if ("POST".equals(e.targetType())) {
            postMapper.update(Wrappers.<TcPost>lambdaUpdate().set(TcPost::getPostStatus, "REMOVED").eq(TcPost::getId, e.targetId()));
        } else if ("COMMENT".equals(e.targetType())) {
            commentMapper.update(Wrappers.<TcPostComment>lambdaUpdate().set(TcPostComment::getCommentStatus, "REMOVED")
                    .eq(TcPostComment::getId, e.targetId()));
        }
    }

    /** 动态流：时间（近 N 天）、学段、学科筛选。学生论坛只看本校。 */
    public PageResult<TcPost> feed(CurrentIdentity ci, String board, Integer days, String period, String subject, PageQuery q) {
        String b = board == null ? XINGTAN : board;
        var w = Wrappers.<TcPost>lambdaQuery().eq(TcPost::getBoard, b).eq(TcPost::getPostStatus, "PUBLISHED")
                .ge(days != null, TcPost::getCreatedAt, days == null ? null : LocalDateTime.now().minusDays(days))
                .eq(period != null, TcPost::getPeriod, period).eq(subject != null, TcPost::getSubject, subject)
                .orderByDesc(TcPost::getCreatedAt);
        if (STUDENT_FORUM.equals(b)) {
            BizException.throwIf(!ci.type().isSchoolMember() && ci.type() != IdentityType.STUDENT && !ci.isPlatform(),
                    ErrorCode.FORBIDDEN);
            if (!ci.isPlatform()) {
                w.eq(TcPost::getSchoolId, ci.tenantId());
            }
        }
        return PageResult.of(postMapper.selectPage(q.toPage(), w));
    }

    public List<TcPost> mine(CurrentIdentity ci) {
        return postMapper.selectList(Wrappers.<TcPost>lambdaQuery().eq(TcPost::getAuthorUserId, ci.userId())
                .orderByDesc(TcPost::getCreatedAt));
    }

    @Transactional
    public TcPostComment comment(CurrentIdentity ci, Long postId, String content) {
        TcPost p = visible(ci, postId);
        BizException.throwIf(content == null || content.isBlank(), ErrorCode.BAD_REQUEST);
        if (safety.check(content).blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        TcPostComment c = new TcPostComment();
        c.setPostId(postId);
        c.setUserId(ci.userId());
        c.setContent(content);
        c.setCommentStatus("PUBLISHED");
        commentMapper.insert(c);
        postMapper.update(Wrappers.<TcPost>lambdaUpdate().setSql("comment_count = comment_count + 1").eq(TcPost::getId, postId));
        if (!p.getAuthorUserId().equals(ci.userId())) {
            notifyApi.send(List.of(p.getAuthorUserId()), "SYSTEM", "你的动态收到了评论", content, "post:" + postId);
        }
        return c;
    }

    public List<TcPostComment> comments(CurrentIdentity ci, Long postId) {
        visible(ci, postId);
        return commentMapper.selectList(Wrappers.<TcPostComment>lambdaQuery().eq(TcPostComment::getPostId, postId)
                .eq(TcPostComment::getCommentStatus, "PUBLISHED").orderByAsc(TcPostComment::getCreatedAt));
    }

    @Transactional
    public boolean like(CurrentIdentity ci, Long postId) {
        TcPost p = visible(ci, postId);
        TcPostLike l = new TcPostLike();
        l.setPostId(postId);
        l.setUserId(ci.userId());
        try {
            likeMapper.insert(l);
            postMapper.update(Wrappers.<TcPost>lambdaUpdate().setSql("like_count = like_count + 1").eq(TcPost::getId, postId));
            if (!p.getAuthorUserId().equals(ci.userId())) {
                notifyApi.send(List.of(p.getAuthorUserId()), "SYSTEM", "你的动态收到了点赞", p.getTitle(), "post:" + postId);
            }
            return true;
        } catch (DuplicateKeyException e) {
            likeMapper.delete(Wrappers.<TcPostLike>lambdaQuery().eq(TcPostLike::getPostId, postId).eq(TcPostLike::getUserId, ci.userId()));
            postMapper.update(Wrappers.<TcPost>lambdaUpdate().setSql("like_count = like_count - 1").eq(TcPost::getId, postId));
            return false;
        }
    }

    /** 作者删除自己的帖子；班主任 / 学校管理员管理本校学生论坛。 */
    public void remove(CurrentIdentity ci, Long postId) {
        TcPost p = BizException.notNull(postMapper.selectById(postId), "帖子");
        boolean manager = STUDENT_FORUM.equals(p.getBoard()) && ci.hasPerm("forum:manage") && Objects.equals(p.getSchoolId(), ci.tenantId());
        BizException.throwIf(!p.getAuthorUserId().equals(ci.userId()) && !manager && !ci.isPlatform(), ErrorCode.FORBIDDEN);
        p.setPostStatus("REMOVED");
        postMapper.updateById(p);
    }

    private TcPost visible(CurrentIdentity ci, Long id) {
        TcPost p = BizException.notNull(postMapper.selectById(id), "帖子");
        boolean ok = "PUBLISHED".equals(p.getPostStatus()) || p.getAuthorUserId().equals(ci.userId());
        if (STUDENT_FORUM.equals(p.getBoard()) && !ci.isPlatform() && !Objects.equals(p.getSchoolId(), ci.tenantId())) {
            ok = false;
        }
        if (!ok) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        return p;
    }
}
