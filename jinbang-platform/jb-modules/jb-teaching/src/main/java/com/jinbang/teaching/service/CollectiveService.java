package com.jinbang.teaching.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.ContentSafetyApi;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.teaching.domain.TcCollectiveComment;
import com.jinbang.teaching.domain.TcCollectiveTheme;
import com.jinbang.teaching.domain.TcMaterial;
import com.jinbang.teaching.mapper.TcCollectiveCommentMapper;
import com.jinbang.teaching.mapper.TcCollectiveThemeMapper;
import com.jinbang.teaching.mapper.TcMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 集体备课（PRD 4.3）：发布主题 → 指定参与教师 → 上传主备稿 → 研讨评论 → 结束；二次备课（P1）；参与统计。
 * 主题属于学校（租户隔离），主备稿复制一份归主题，不随组织者个人删除而消失。
 */
@Service
@RequiredArgsConstructor
public class CollectiveService {
    private final TcCollectiveThemeMapper themeMapper;
    private final TcCollectiveCommentMapper commentMapper;
    private final TcMaterialMapper materialMapper;
    private final PrepService prepService;
    private final FileStorage storage;
    private final NotifyApi notifyApi;
    private final ContentSafetyApi safety;

    public record ThemeReq(String title, String subject, String description, List<Long> memberUserIds, LocalDateTime deadline) {
    }

    @Transactional
    public TcCollectiveTheme publish(CurrentIdentity ci, ThemeReq req) {
        BizException.throwIf(req.title() == null || req.title().isBlank(), ErrorCode.BAD_REQUEST, "请填写研讨主题");
        BizException.throwIf(req.memberUserIds() == null || req.memberUserIds().isEmpty(), ErrorCode.BAD_REQUEST, "请选择参与教师");
        Set<Long> members = new LinkedHashSet<>(req.memberUserIds());
        members.add(ci.userId());
        TcCollectiveTheme t = new TcCollectiveTheme();
        t.setOrganizerUserId(ci.userId());
        t.setTitle(req.title());
        t.setSubject(req.subject());
        t.setDescription(req.description());
        t.setMemberUserIds(members.stream().map(String::valueOf).collect(Collectors.joining(",")));
        t.setThemeStatus("ONGOING");
        t.setDeadline(req.deadline());
        themeMapper.insert(t);
        notifyApi.send(req.memberUserIds(), "SYSTEM", "集体备课邀请：" + t.getTitle(),
                "你被邀请参加集体备课研讨" + (t.getDeadline() == null ? "" : "，截止 " + t.getDeadline()), "collective:" + t.getId());
        return t;
    }

    public List<TcCollectiveTheme> mine(CurrentIdentity ci) {
        return themeMapper.selectList(Wrappers.<TcCollectiveTheme>lambdaQuery().orderByDesc(TcCollectiveTheme::getCreatedAt))
                .stream().filter(t -> members(t).contains(ci.userId())).toList();
    }

    /** 上传主备稿：从组织者的备课资料复制一份归主题。 */
    @Transactional
    public TcCollectiveTheme setMainDraft(CurrentIdentity ci, Long themeId, Long materialId) {
        TcCollectiveTheme t = member(ci, themeId);
        BizException.throwIf(!t.getOrganizerUserId().equals(ci.userId()), ErrorCode.FORBIDDEN, "只有主备人可以上传主备稿");
        BizException.throwIf(!"ONGOING".equals(t.getThemeStatus()), ErrorCode.STATE_TRANSITION_DENIED, "研讨已结束");
        TcMaterial src = prepService.own(ci.userId(), materialId);
        String ext = PrepService.ext(src.getFileName());
        TcMaterial draft = new TcMaterial();
        draft.setOwnerUserId(0L);
        draft.setFileName(src.getFileName());
        draft.setFileType(src.getFileType());
        draft.setStorageKey(storage.put("collective", ext, storage.get(src.getStorageKey())));
        draft.setSizeBytes(src.getSizeBytes());
        draft.setSource("COLLECTIVE");
        draft.setSourceRef(String.valueOf(themeId));
        draft.setVersionNo(1);
        draft.setDeleted(false);
        materialMapper.insert(draft);
        t.setMainMaterialId(draft.getId());
        themeMapper.updateById(t);
        return t;
    }

    public TcCollectiveComment comment(CurrentIdentity ci, Long themeId, String content) {
        TcCollectiveTheme t = member(ci, themeId);
        BizException.throwIf(!"ONGOING".equals(t.getThemeStatus()), ErrorCode.STATE_TRANSITION_DENIED, "研讨已结束");
        BizException.throwIf(content == null || content.isBlank(), ErrorCode.BAD_REQUEST);
        if (safety.check(content).blocked()) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        TcCollectiveComment c = new TcCollectiveComment();
        c.setThemeId(themeId);
        c.setUserId(ci.userId());
        c.setContent(content);
        commentMapper.insert(c);
        return c;
    }

    public List<TcCollectiveComment> comments(CurrentIdentity ci, Long themeId) {
        member(ci, themeId);
        return commentMapper.selectList(Wrappers.<TcCollectiveComment>lambdaQuery().eq(TcCollectiveComment::getThemeId, themeId)
                .orderByAsc(TcCollectiveComment::getCreatedAt));
    }

    public TcCollectiveTheme finish(CurrentIdentity ci, Long themeId) {
        TcCollectiveTheme t = member(ci, themeId);
        BizException.throwIf(!t.getOrganizerUserId().equals(ci.userId()), ErrorCode.FORBIDDEN);
        t.setThemeStatus("FINISHED");
        t.setFinishedAt(LocalDateTime.now());
        themeMapper.updateById(t);
        return t;
    }

    /** 二次备课：把主备稿复制到自己的备课目录再修改。 */
    public TcMaterial secondPrep(CurrentIdentity ci, Long themeId, Long catalogId) {
        TcCollectiveTheme t = member(ci, themeId);
        BizException.throwIf(t.getMainMaterialId() == null, ErrorCode.NOT_FOUND, "该主题还没有主备稿");
        TcMaterial draft = materialMapper.selectById(t.getMainMaterialId());
        String ext = PrepService.ext(draft.getFileName());
        byte[] bytes = storage.get(draft.getStorageKey());
        return prepService.add(ci.userId(), catalogId, draft.getFileName(), draft.getFileType(), storage.put("prep", ext, bytes),
                bytes.length, "COLLECTIVE", String.valueOf(themeId));
    }

    /** 参与统计：每位成员的研讨发言数。 */
    public Map<Long, Long> stats(CurrentIdentity ci, Long themeId) {
        TcCollectiveTheme t = member(ci, themeId);
        Map<Long, Long> out = new LinkedHashMap<>();
        members(t).forEach(u -> out.put(u, 0L));
        comments(ci, themeId).forEach(c -> out.merge(c.getUserId(), 1L, Long::sum));
        return out;
    }

    private TcCollectiveTheme member(CurrentIdentity ci, Long id) {
        TcCollectiveTheme t = BizException.notNull(themeMapper.selectById(id), "研讨主题");
        if (!members(t).contains(ci.userId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return t;
    }

    private static Set<Long> members(TcCollectiveTheme t) {
        return Arrays.stream(t.getMemberUserIds().split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
