package com.jinbang.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.resource.domain.QPaper;
import com.jinbang.resource.domain.QQuestion;
import com.jinbang.resource.domain.ResBasket;
import com.jinbang.resource.domain.ResCatalog;
import com.jinbang.resource.domain.ResCollectionActivity;
import com.jinbang.resource.domain.ResComment;
import com.jinbang.resource.domain.ResFavorite;
import com.jinbang.resource.domain.ResResource;
import com.jinbang.resource.service.PaperService;
import com.jinbang.resource.service.QuestionService;
import com.jinbang.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ResourceController {
    private final ResourceService resourceService;
    private final QuestionService questionService;
    private final PaperService paperService;
    private final ExportLinkApi exportLinkApi;
    private final FileStorage storage;

    public record ResourceIdsReq(List<Long> ids) {
    }

    public record FavReq(String targetType, Long targetId, String folder, boolean on) {
    }

    public record CommentReq(String type, String content) {
    }

    public record QuestionBatchReq(List<Long> ids, String op, String value) {
    }

    public record ImportReq(String text, String period, String subject, Long catalogId) {
    }

    public record DirReq(Long parentId, String name, String period, String subject) {
    }

    public record PaperCreateReq(String title, String subject) {
    }

    // ---------------- 资源 ----------------

    @GetMapping("/api/resources")
    public R<PageResult<ResResource>> search(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String scope,
                                             @RequestParam(required = false) String period,
                                             @RequestParam(required = false) String subject,
                                             @RequestParam(required = false) String resType,
                                             @RequestParam(required = false) String level,
                                             @RequestParam(required = false) Long catalogId,
                                             @RequestParam(required = false) Integer year,
                                             @RequestParam(required = false) String region,
                                             @RequestParam(required = false) String sort, PageQuery page) {
        return R.ok(resourceService.search(LoginHelper.require(), new ResourceService.ResourceQuery(keyword, scope, period,
                subject, resType, level, catalogId, year, region, sort, page)));
    }

    @GetMapping("/api/resources/{id}")
    public R<ResResource> detail(@PathVariable Long id) {
        return R.ok(resourceService.detail(LoginHelper.require(), id));
    }

    @PostMapping("/api/resources")
    public R<ResResource> upload(@RequestPart("meta") ResourceService.UploadReq meta,
                                 @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        return R.ok(resourceService.upload(LoginHelper.require(), meta, file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getBytes()));
    }

    @PostMapping("/api/resources/{id}/submit")
    public R<ResResource> submit(@PathVariable Long id) {
        return R.ok(resourceService.submit(LoginHelper.require(), id));
    }

    @PostMapping("/api/resources/{id}/shelf")
    public R<ResResource> shelf(@PathVariable Long id, @RequestParam boolean online) {
        return R.ok(resourceService.changeShelf(LoginHelper.require(), id, online));
    }

    /** 下载：返回带身份绑定的短时下载链接。付费资源首次下载扣币。 */
    @PostMapping("/api/resources/{id}/download")
    public R<Map<String, Object>> download(@PathVariable Long id) {
        ResourceService.DownloadResult d = resourceService.download(LoginHelper.require(), id);
        return R.ok(Map.of("url", exportLinkApi.createLink(d.fileKey(), d.fileName(), Duration.ofMinutes(30)),
                "paidCoins", d.paidCoins()));
    }

    @GetMapping("/api/resources/downloads")
    public R<PageResult<ResResource>> myDownloads(PageQuery q) {
        return R.ok(resourceService.myDownloads(LoginHelper.require(), q));
    }

    @PostMapping("/api/favorites")
    public R<Void> favorite(@RequestBody FavReq req) {
        resourceService.favorite(LoginHelper.require(), req.targetType(), req.targetId(), req.folder(), req.on());
        return R.ok();
    }

    @GetMapping("/api/favorites")
    public R<List<ResFavorite>> favorites(@RequestParam(required = false) String targetType) {
        return R.ok(resourceService.favorites(LoginHelper.require(), targetType));
    }

    /** 资源篮 / 试题篮：itemType = RESOURCE / QUESTION。 */
    @PostMapping("/api/basket/{itemType}/add")
    public R<Void> basketAdd(@PathVariable String itemType, @RequestBody ResourceIdsReq req) {
        resourceService.basketAdd(LoginHelper.require(), itemType.toUpperCase(), req.ids());
        return R.ok();
    }

    @PostMapping("/api/basket/{itemType}/remove")
    public R<Void> basketRemove(@PathVariable String itemType, @RequestBody ResourceIdsReq req) {
        resourceService.basketRemove(LoginHelper.require(), itemType.toUpperCase(), req.ids());
        return R.ok();
    }

    @GetMapping("/api/basket/{itemType}")
    public R<List<ResBasket>> basket(@PathVariable String itemType) {
        return R.ok(resourceService.basket(LoginHelper.require(), itemType.toUpperCase()));
    }

    @PostMapping("/api/resources/{id}/comments")
    public R<ResComment> comment(@PathVariable Long id, @RequestBody CommentReq req) {
        return R.ok(resourceService.comment(LoginHelper.require(), id, req.type(), req.content()));
    }

    @GetMapping("/api/resources/{id}/comments")
    public R<List<ResComment>> comments(@PathVariable Long id) {
        return R.ok(resourceService.comments(id));
    }

    // ---------------- 资源征集 ----------------

    @GetMapping("/api/collection/activities")
    public R<List<ResCollectionActivity>> activities() {
        return R.ok(resourceService.activities());
    }

    @SaCheckPermission("op:resource")
    @PostMapping("/api/admin/collection/activities")
    public R<ResCollectionActivity> saveActivity(@RequestBody ResCollectionActivity a) {
        return R.ok(resourceService.saveActivity(a));
    }

    @GetMapping("/api/collection/mine")
    public R<List<ResResource>> mySubmissions() {
        return R.ok(resourceService.mySubmissions(LoginHelper.require()));
    }

    // ---------------- 题库 ----------------

    @GetMapping("/api/questions")
    public R<PageResult<QQuestion>> questions(@RequestParam(required = false) String scope,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(required = false) String subject,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String difficulty,
                                              @RequestParam(required = false) String source,
                                              @RequestParam(required = false) String knowledge,
                                              @RequestParam(required = false) Long catalogId,
                                              @RequestParam(required = false) Integer year,
                                              @RequestParam(required = false) String keyword, PageQuery page) {
        return R.ok(questionService.list(LoginHelper.require(), new QuestionService.QuestionQuery(scope, period, subject, type,
                difficulty, source, knowledge, catalogId, year, keyword, page)));
    }

    @GetMapping("/api/questions/{id}")
    public R<QQuestion> question(@PathVariable Long id) {
        return R.ok(questionService.get(LoginHelper.require(), id));
    }

    @PostMapping("/api/questions")
    public R<QQuestion> createQuestion(@RequestBody QuestionService.QuestionReq req) {
        return R.ok(questionService.create(LoginHelper.require(), req));
    }

    @PutMapping("/api/questions/{id}")
    public R<QQuestion> updateQuestion(@PathVariable Long id, @RequestBody QuestionService.QuestionReq req) {
        return R.ok(questionService.update(LoginHelper.require(), id, req));
    }

    @PostMapping("/api/questions/batch")
    public R<Integer> batch(@RequestBody QuestionBatchReq req) {
        return R.ok(questionService.batch(LoginHelper.require(), req.ids(), req.op(), req.value()));
    }

    @PostMapping("/api/questions/{id}/collect")
    public R<QQuestion> collect(@PathVariable Long id, @RequestParam(required = false) Long catalogId) {
        return R.ok(questionService.collect(LoginHelper.require(), id, catalogId));
    }

    @GetMapping("/api/questions/{id}/similar")
    public R<List<QQuestion>> similar(@PathVariable Long id) {
        return R.ok(questionService.similar(LoginHelper.require(), id, 5));
    }

    @PostMapping("/api/questions/import/preview")
    public R<QuestionService.ImportPreview> preview(@RequestBody ImportReq req) {
        return R.ok(questionService.preview(req.text()));
    }

    @PostMapping("/api/questions/import")
    public R<List<Long>> importText(@RequestBody ImportReq req) {
        return R.ok(questionService.importText(LoginHelper.require(), req.text(), req.period(), req.subject(), req.catalogId()));
    }

    /** 目录树：TEXTBOOK 教材 / KNOWLEDGE 知识点 / QUESTION_DIR 我的题库目录。 */
    @GetMapping("/api/catalogs")
    public R<List<ResCatalog>> catalogs(@RequestParam String type, @RequestParam(required = false) String period,
                                        @RequestParam(required = false) String subject) {
        return R.ok(questionService.catalogs(LoginHelper.require(), type, period, subject));
    }

    @PostMapping("/api/question-dirs")
    public R<ResCatalog> newDir(@RequestBody DirReq req) {
        return R.ok(questionService.saveDir(LoginHelper.require(), null, req.parentId(), req.name(), req.period(), req.subject()));
    }

    @PutMapping("/api/question-dirs/{id}")
    public R<ResCatalog> renameDir(@PathVariable Long id, @RequestBody DirReq req) {
        return R.ok(questionService.saveDir(LoginHelper.require(), id, null, req.name(), null, null));
    }

    @PostMapping("/api/question-dirs/{id}/delete")
    public R<Void> deleteDir(@PathVariable Long id) {
        questionService.deleteDir(LoginHelper.require(), id);
        return R.ok();
    }

    // ---------------- 组卷 ----------------

    @PostMapping("/api/papers/from-basket")
    public R<PaperService.PaperView> fromBasket(@RequestBody PaperCreateReq req) {
        return R.ok(paperService.createFromBasket(LoginHelper.require(), req.title(), req.subject()));
    }

    @PostMapping("/api/papers/smart")
    public R<PaperService.PaperView> smart(@RequestBody PaperService.SmartReq req) {
        return R.ok(paperService.smart(LoginHelper.require(), req));
    }

    @GetMapping("/api/papers")
    public R<List<QPaper>> papers(@RequestParam(defaultValue = "false") boolean recycle) {
        return R.ok(paperService.myPapers(LoginHelper.require(), recycle));
    }

    @GetMapping("/api/papers/{id}")
    public R<PaperService.PaperView> paper(@PathVariable Long id) {
        return R.ok(paperService.view(LoginHelper.require(), id));
    }

    @PostMapping("/api/papers/{id}/scores")
    public R<PaperService.PaperView> scores(@PathVariable Long id, @RequestBody Map<Long, BigDecimal> scores) {
        return R.ok(paperService.setScores(LoginHelper.require(), id, scores));
    }

    @PostMapping("/api/papers/{id}/reorder")
    public R<PaperService.PaperView> reorder(@PathVariable Long id, @RequestBody ResourceIdsReq req) {
        return R.ok(paperService.reorder(LoginHelper.require(), id, req.ids()));
    }

    @PostMapping("/api/papers/{id}/items/{itemId}/replace")
    public R<PaperService.PaperView> replace(@PathVariable Long id, @PathVariable Long itemId) {
        return R.ok(paperService.replace(LoginHelper.require(), id, itemId));
    }

    @PostMapping("/api/papers/{id}/recycle")
    public R<Void> recycle(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean deleted) {
        paperService.recycle(LoginHelper.require(), id, deleted);
        return R.ok();
    }

    @PostMapping("/api/papers/{id}/export")
    public R<String> export(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean withAnswer) {
        var ci = LoginHelper.require();
        byte[] docx = paperService.exportDocx(ci, id, withAnswer);
        String key = storage.put("paper-export", "docx", docx);
        return R.ok(exportLinkApi.createLink(key, paperService.view(ci, id).paper().getTitle() + ".docx", Duration.ofHours(1)));
    }
}
