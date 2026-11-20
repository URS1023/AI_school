package com.jinbang.ai.kb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.ai.domain.AiKb;
import com.jinbang.ai.domain.AiKbChunk;
import com.jinbang.ai.domain.AiKbDoc;
import com.jinbang.ai.domain.AiKbMember;
import com.jinbang.ai.mapper.AiKbChunkMapper;
import com.jinbang.ai.mapper.AiKbDocMapper;
import com.jinbang.ai.mapper.AiKbMapper;
import com.jinbang.ai.mapper.AiKbMemberMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 个人 / 共享知识库（PRD 7.7）：上传 → 解析（解析中 / 可用 / 失败）→ 切片 → 检索引用。
 * 检索先用字词重叠打分，接口不变，后续可换向量库（Milvus / pgvector）。
 */
@Service
@RequiredArgsConstructor
public class KbService {
    private static final int CHUNK = 800;

    private final AiKbMapper kbMapper;
    private final AiKbMemberMapper memberMapper;
    private final AiKbDocMapper docMapper;
    private final AiKbChunkMapper chunkMapper;
    private final FileStorage storage;

    public record Snippet(Long docId, String fileName, String content, double score) {
    }

    public record KbView(AiKb kb, String myRole, long usedBytes) {
    }

    @Transactional
    public AiKb create(Long userId, String name, String intro, boolean shared) {
        AiKb kb = new AiKb();
        kb.setOwnerUserId(userId);
        kb.setKbType(shared ? "SHARED" : "PERSONAL");
        kb.setKbName(name);
        kb.setIntro(intro);
        kb.setQuotaBytes(100L * 1024 * 1024);
        kbMapper.insert(kb);
        AiKbMember m = new AiKbMember();
        m.setKbId(kb.getId());
        m.setUserId(userId);
        m.setMemberRole("OWNER");
        memberMapper.insert(m);
        return kb;
    }

    public List<KbView> mine(Long userId) {
        List<AiKbMember> ms = memberMapper.selectList(Wrappers.<AiKbMember>lambdaQuery().eq(AiKbMember::getUserId, userId));
        List<KbView> out = new ArrayList<>();
        for (AiKbMember m : ms) {
            AiKb kb = kbMapper.selectById(m.getKbId());
            if (kb != null) {
                out.add(new KbView(kb, m.getMemberRole(), used(kb.getId())));
            }
        }
        return out;
    }

    public void addMember(Long userId, Long kbId, Long memberUserId, String role) {
        requireRole(userId, kbId, "OWNER");
        BizException.throwIf(!"SHARED".equals(kbMapper.selectById(kbId).getKbType()), ErrorCode.BAD_REQUEST, "个人知识库不能添加成员");
        AiKbMember m = new AiKbMember();
        m.setKbId(kbId);
        m.setUserId(memberUserId);
        m.setMemberRole("EDITOR".equals(role) ? "EDITOR" : "VIEWER");
        memberMapper.insert(m);
    }

    @Transactional
    public AiKbDoc upload(Long userId, Long kbId, String folder, String fileName, byte[] bytes) {
        requireRole(userId, kbId, "OWNER", "EDITOR");
        AiKb kb = kbMapper.selectById(kbId);
        BizException.throwIf(used(kbId) + bytes.length > kb.getQuotaBytes(), ErrorCode.BAD_REQUEST, "知识库容量不足");
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        AiKbDoc doc = new AiKbDoc();
        doc.setKbId(kbId);
        doc.setFolder(folder == null || folder.isBlank() ? "/" : folder);
        doc.setFileName(fileName);
        doc.setStorageKey(storage.put("kb", ext, bytes));
        doc.setSizeBytes((long) bytes.length);
        doc.setParseStatus("PARSING");
        doc.setChunkCount(0);
        docMapper.insert(doc);
        try {
            String text = extract(ext, bytes);
            if (text.isBlank()) {
                throw new IllegalArgumentException("文件中没有可识别的文字");
            }
            int n = 0;
            for (int i = 0; i < text.length(); i += CHUNK) {
                AiKbChunk c = new AiKbChunk();
                c.setKbId(kbId);
                c.setDocId(doc.getId());
                c.setChunkNo(n++);
                c.setContent(text.substring(i, Math.min(text.length(), i + CHUNK)));
                chunkMapper.insert(c);
            }
            doc.setChunkCount(n);
            doc.setParseStatus("READY");
        } catch (Exception e) {
            doc.setParseStatus("FAILED");
            doc.setParseError(e.getMessage() == null ? "解析失败" : e.getMessage().substring(0, Math.min(200, e.getMessage().length())));
        }
        docMapper.updateById(doc);
        return doc;
    }

    public List<AiKbDoc> docs(Long userId, Long kbId, String folder, String keyword) {
        requireRole(userId, kbId, "OWNER", "EDITOR", "VIEWER");
        return docMapper.selectList(Wrappers.<AiKbDoc>lambdaQuery().eq(AiKbDoc::getKbId, kbId)
                .eq(folder != null, AiKbDoc::getFolder, folder).like(keyword != null, AiKbDoc::getFileName, keyword)
                .orderByDesc(AiKbDoc::getCreatedAt));
    }

    public void renameOrMove(Long userId, Long docId, String newName, String newFolder) {
        AiKbDoc d = BizException.notNull(docMapper.selectById(docId), "文件");
        requireRole(userId, d.getKbId(), "OWNER", "EDITOR");
        if (newName != null && !newName.isBlank()) {
            d.setFileName(newName);
        }
        if (newFolder != null && !newFolder.isBlank()) {
            d.setFolder(newFolder);
        }
        docMapper.updateById(d);
    }

    @Transactional
    public void deleteDoc(Long userId, Long docId) {
        AiKbDoc d = BizException.notNull(docMapper.selectById(docId), "文件");
        requireRole(userId, d.getKbId(), "OWNER", "EDITOR");
        chunkMapper.delete(Wrappers.<AiKbChunk>lambdaQuery().eq(AiKbChunk::getDocId, docId));
        docMapper.deleteById(docId);
        storage.delete(d.getStorageKey());
    }

    /** 检索：只在当前用户有权访问的知识库里查。 */
    public List<Snippet> retrieve(Long userId, List<Long> kbIds, String query, int topK) {
        List<Long> allowed = kbIds.stream().filter(id -> role(userId, id) != null).toList();
        if (allowed.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> grams = grams(query);
        List<Snippet> scored = new ArrayList<>();
        for (AiKbChunk c : chunkMapper.selectList(Wrappers.<AiKbChunk>lambdaQuery().in(AiKbChunk::getKbId, allowed))) {
            Set<String> cg = grams(c.getContent());
            long hit = grams.stream().filter(cg::contains).count();
            if (hit > 0) {
                AiKbDoc d = docMapper.selectById(c.getDocId());
                scored.add(new Snippet(c.getDocId(), d == null ? "" : d.getFileName(), c.getContent(),
                        hit / (double) grams.size()));
            }
        }
        scored.sort(Comparator.comparingDouble(Snippet::score).reversed());
        return scored.stream().limit(topK).toList();
    }

    private static Set<String> grams(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：“”（）《》]", "");
        Set<String> g = new HashSet<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            g.add(t.substring(i, i + 2));
        }
        return g;
    }

    private long used(Long kbId) {
        return docMapper.selectList(Wrappers.<AiKbDoc>lambdaQuery().eq(AiKbDoc::getKbId, kbId))
                .stream().mapToLong(AiKbDoc::getSizeBytes).sum();
    }

    private String role(Long userId, Long kbId) {
        AiKbMember m = memberMapper.selectOne(Wrappers.<AiKbMember>lambdaQuery().eq(AiKbMember::getKbId, kbId)
                .eq(AiKbMember::getUserId, userId));
        return m == null ? null : m.getMemberRole();
    }

    private void requireRole(Long userId, Long kbId, String... roles) {
        String r = role(userId, kbId);
        if (r == null || !List.of(roles).contains(r)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    static String extract(String ext, byte[] bytes) throws Exception {
        return switch (ext) {
            case "txt", "md", "csv" -> new String(bytes, StandardCharsets.UTF_8);
            case "docx" -> {
                try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(bytes));
                     XWPFWordExtractor ex = new XWPFWordExtractor(d)) {
                    yield ex.getText();
                }
            }
            case "pdf" -> {
                try (PDDocument d = Loader.loadPDF(bytes)) {
                    yield new PDFTextStripper().getText(d);
                }
            }
            case "pptx" -> {
                StringBuilder sb = new StringBuilder();
                try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
                    for (XSLFSlide s : ppt.getSlides()) {
                        for (XSLFShape sh : s.getShapes()) {
                            if (sh instanceof XSLFTextShape ts) {
                                sb.append(ts.getText()).append('\n');
                            }
                        }
                    }
                }
                yield sb.toString();
            }
            default -> throw new IllegalArgumentException("暂不支持该文件格式，请上传 txt / md / docx / pdf / pptx");
        };
    }
}
