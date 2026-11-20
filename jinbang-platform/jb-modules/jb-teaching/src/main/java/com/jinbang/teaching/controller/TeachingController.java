package com.jinbang.teaching.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.teaching.domain.TcCollectiveComment;
import com.jinbang.teaching.domain.TcCollectiveTheme;
import com.jinbang.teaching.domain.TcMaterial;
import com.jinbang.teaching.domain.TcMaterialVersion;
import com.jinbang.teaching.domain.TcPost;
import com.jinbang.teaching.domain.TcPostComment;
import com.jinbang.teaching.domain.TcPrepCatalog;
import com.jinbang.teaching.service.CollectiveService;
import com.jinbang.teaching.service.CommunityService;
import com.jinbang.teaching.service.PrepService;
import com.jinbang.teaching.service.SmartPrepService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TeachingController {
    private final PrepService prepService;
    private final SmartPrepService smartPrepService;
    private final CollectiveService collectiveService;
    private final CommunityService communityService;
    private final ExportLinkApi exportLinkApi;

    public record CatalogReq(Long parentId, String name, String period, String subject, String edition) {
    }

    public record BookReq(String bookName, List<String> courses, String period, String subject, String edition) {
    }

    public record MoveReq(String name, Long parentId, Long catalogId) {
    }

    public record BlankReq(Long catalogId, String name, String type) {
    }

    public record FromResourceReq(Long catalogId, Long resourceId) {
    }

    public record GenerateReq(String template) {
    }

    public record PlanReq(Long catalogId, String topic, String requirement) {
    }

    public record MaterialReq(Long materialId, Long catalogId) {
    }

    public record TextReq(String content) {
    }

    // ---------------- 备课目录与资料 ----------------

    @SaCheckPermission("prep:use")
    @GetMapping("/api/prep/catalogs")
    public R<List<TcPrepCatalog>> catalogs(@RequestParam(required = false) String period,
                                          @RequestParam(required = false) String subject) {
        return R.ok(prepService.catalogs(LoginHelper.userId(), period, subject));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/catalogs")
    public R<TcPrepCatalog> createCatalog(@RequestBody CatalogReq r) {
        return R.ok(prepService.createCatalog(LoginHelper.userId(), r.parentId(), r.name(), r.period(), r.subject(), r.edition()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/catalogs/by-book")
    public R<TcPrepCatalog> byBook(@RequestBody BookReq r) {
        return R.ok(prepService.createByBook(LoginHelper.userId(), r.bookName(), r.courses(), r.period(), r.subject(), r.edition()));
    }

    @SaCheckPermission("prep:use")
    @PutMapping("/api/prep/catalogs/{id}")
    public R<Void> moveCatalog(@PathVariable Long id, @RequestBody MoveReq r) {
        prepService.renameOrMoveCatalog(LoginHelper.userId(), id, r.name(), r.parentId());
        return R.ok();
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/catalogs/{id}/delete")
    public R<Void> deleteCatalog(@PathVariable Long id) {
        prepService.deleteCatalog(LoginHelper.userId(), id);
        return R.ok();
    }

    @SaCheckPermission("prep:use")
    @GetMapping("/api/prep/materials")
    public R<List<TcMaterial>> materials(@RequestParam(required = false) Long catalogId,
                                         @RequestParam(required = false) String fileType,
                                         @RequestParam(defaultValue = "false") boolean recycle) {
        return R.ok(prepService.materials(LoginHelper.userId(), catalogId, fileType, recycle));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/upload")
    public R<TcMaterial> upload(@RequestParam(required = false) Long catalogId, @RequestParam("file") MultipartFile file)
            throws IOException {
        return R.ok(prepService.upload(LoginHelper.userId(), catalogId, file.getOriginalFilename(), file.getBytes()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/blank")
    public R<TcMaterial> blank(@RequestBody BlankReq r) {
        return R.ok(prepService.createBlank(LoginHelper.userId(), r.catalogId(), r.name(), r.type()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/from-resource")
    public R<TcMaterial> fromResource(@RequestBody FromResourceReq r) {
        return R.ok(prepService.fromResource(LoginHelper.require(), r.catalogId(), r.resourceId()));
    }

    @SaCheckPermission("prep:use")
    @PutMapping("/api/prep/materials/{id}")
    public R<TcMaterial> updateMaterial(@PathVariable Long id, @RequestBody MoveReq r) {
        return R.ok(prepService.update(LoginHelper.userId(), id, r.name(), r.catalogId()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/{id}/copy")
    public R<TcMaterial> copy(@PathVariable Long id) {
        return R.ok(prepService.copy(LoginHelper.userId(), id));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/{id}/recycle")
    public R<Void> recycle(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean deleted) {
        prepService.recycle(LoginHelper.userId(), id, deleted);
        return R.ok();
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/recycle/purge")
    public R<Integer> purge() {
        return R.ok(prepService.purge(LoginHelper.userId()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/{id}/versions")
    public R<TcMaterial> saveVersion(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(prepService.saveVersion(LoginHelper.userId(), id, file.getBytes()));
    }

    @SaCheckPermission("prep:use")
    @GetMapping("/api/prep/materials/{id}/versions")
    public R<List<TcMaterialVersion>> versions(@PathVariable Long id) {
        return R.ok(prepService.versions(LoginHelper.userId(), id));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/{id}/versions/{no}/restore")
    public R<TcMaterial> restore(@PathVariable Long id, @PathVariable int no) {
        return R.ok(prepService.restoreVersion(LoginHelper.userId(), id, no));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/materials/{id}/download")
    public R<String> download(@PathVariable Long id) {
        TcMaterial m = prepService.own(LoginHelper.userId(), id);
        return R.ok(exportLinkApi.createLink(m.getStorageKey(), m.getFileName(), Duration.ofMinutes(30)));
    }

    // ---------------- 智能备课 ----------------

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/smart")
    public R<SmartPrepService.PrepView> smartStart(@RequestBody SmartPrepService.StartReq req) {
        return R.ok(smartPrepService.start(LoginHelper.require(), req));
    }

    @SaCheckPermission("prep:use")
    @PutMapping("/api/prep/smart/{id}/outline")
    public R<SmartPrepService.PrepView> editOutline(@PathVariable Long id, @RequestBody JsonNode outline) {
        return R.ok(smartPrepService.editOutline(LoginHelper.require(), id, outline));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/smart/{id}/generate")
    public R<SmartPrepService.PrepView> generate(@PathVariable Long id, @RequestBody GenerateReq req) {
        return R.ok(smartPrepService.generate(LoginHelper.require(), id, req.template()));
    }

    @SaCheckPermission("prep:use")
    @PostMapping("/api/prep/lesson-plan")
    public R<TcMaterial> lessonPlan(@RequestBody PlanReq req) {
        return R.ok(smartPrepService.lessonPlan(LoginHelper.require(), req.catalogId(), req.topic(), req.requirement()));
    }

    // ---------------- 集体备课 ----------------

    @SaCheckPermission("collective:create")
    @PostMapping("/api/collective/themes")
    public R<TcCollectiveTheme> publishTheme(@RequestBody CollectiveService.ThemeReq req) {
        return R.ok(collectiveService.publish(LoginHelper.require(), req));
    }

    @GetMapping("/api/collective/themes")
    public R<List<TcCollectiveTheme>> themes() {
        return R.ok(collectiveService.mine(LoginHelper.require()));
    }

    @PostMapping("/api/collective/themes/{id}/main-draft")
    public R<TcCollectiveTheme> mainDraft(@PathVariable Long id, @RequestBody MaterialReq req) {
        return R.ok(collectiveService.setMainDraft(LoginHelper.require(), id, req.materialId()));
    }

    @PostMapping("/api/collective/themes/{id}/comments")
    public R<TcCollectiveComment> themeComment(@PathVariable Long id, @RequestBody TextReq req) {
        return R.ok(collectiveService.comment(LoginHelper.require(), id, req.content()));
    }

    @GetMapping("/api/collective/themes/{id}/comments")
    public R<List<TcCollectiveComment>> themeComments(@PathVariable Long id) {
        return R.ok(collectiveService.comments(LoginHelper.require(), id));
    }

    @PostMapping("/api/collective/themes/{id}/finish")
    public R<TcCollectiveTheme> finish(@PathVariable Long id) {
        return R.ok(collectiveService.finish(LoginHelper.require(), id));
    }

    @PostMapping("/api/collective/themes/{id}/second-prep")
    public R<TcMaterial> secondPrep(@PathVariable Long id, @RequestBody MaterialReq req) {
        return R.ok(collectiveService.secondPrep(LoginHelper.require(), id, req.catalogId()));
    }

    @GetMapping("/api/collective/themes/{id}/stats")
    public R<Map<Long, Long>> stats(@PathVariable Long id) {
        return R.ok(collectiveService.stats(LoginHelper.require(), id));
    }

    // ---------------- 社区 ----------------

    @PostMapping("/api/community/posts")
    public R<TcPost> post(@RequestBody CommunityService.PostReq req) {
        CurrentIdentity ci = LoginHelper.require();
        return R.ok(communityService.publish(ci, req, null));
    }

    @GetMapping("/api/community/posts")
    public R<PageResult<TcPost>> feed(@RequestParam(required = false) String board, @RequestParam(required = false) Integer days,
                                      @RequestParam(required = false) String period,
                                      @RequestParam(required = false) String subject, PageQuery q) {
        return R.ok(communityService.feed(LoginHelper.require(), board, days, period, subject, q));
    }

    @GetMapping("/api/community/posts/mine")
    public R<List<TcPost>> minePosts() {
        return R.ok(communityService.mine(LoginHelper.require()));
    }

    @PostMapping("/api/community/posts/{id}/comments")
    public R<TcPostComment> comment(@PathVariable Long id, @RequestBody TextReq req) {
        return R.ok(communityService.comment(LoginHelper.require(), id, req.content()));
    }

    @GetMapping("/api/community/posts/{id}/comments")
    public R<List<TcPostComment>> comments(@PathVariable Long id) {
        return R.ok(communityService.comments(LoginHelper.require(), id));
    }

    @PostMapping("/api/community/posts/{id}/like")
    public R<Boolean> like(@PathVariable Long id) {
        return R.ok(communityService.like(LoginHelper.require(), id));
    }

    @PostMapping("/api/community/posts/{id}/remove")
    public R<Void> remove(@PathVariable Long id) {
        communityService.remove(LoginHelper.require(), id);
        return R.ok();
    }
}
