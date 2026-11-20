package com.jinbang.teaching.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.storage.FileStorage;
import com.jinbang.teaching.domain.TcMaterial;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 备课资料在线预览（前端方案 7.4）：PPTX 逐页渲染成图片（按资料 + 版本缓存），DOCX 转简单 HTML，PDF 直接给链接。
 * 接入 WPS WebOffice 后在线编辑走 WPS，这里保留作为不依赖第三方的兜底预览。
 */
@Service
@RequiredArgsConstructor
public class PreviewService {
    private static final int MAX_SLIDES = 80;

    private final PrepService prepService;
    private final FileStorage storage;
    private final ExportLinkApi exportLinkApi;
    private final Cache<String, List<String>> slideKeys = Caffeine.newBuilder().maximumSize(2000)
            .expireAfterAccess(Duration.ofDays(1)).build();

    /** @param type IMAGES / HTML / PDF / DOWNLOAD */
    public record Preview(String type, List<String> urls, String html) {
    }

    public Preview preview(Long userId, Long materialId) {
        TcMaterial m = prepService.own(userId, materialId);
        return switch (m.getFileType()) {
            case "PPTX" -> {
                List<String> keys = slideKeys.get(m.getId() + ":" + m.getVersionNo(), k -> renderSlides(m));
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) {
                    urls.add(exportLinkApi.createLink(keys.get(i), "slide-" + (i + 1) + ".png", Duration.ofMinutes(30)));
                }
                yield new Preview("IMAGES", urls, null);
            }
            case "DOCX" -> new Preview("HTML", List.of(), docxHtml(m));
            case "PDF" -> new Preview("PDF", List.of(exportLinkApi.createLink(m.getStorageKey(), m.getFileName(),
                    Duration.ofMinutes(30))), null);
            default -> new Preview("DOWNLOAD", List.of(exportLinkApi.createLink(m.getStorageKey(), m.getFileName(),
                    Duration.ofMinutes(30))), null);
        };
    }

    private List<String> renderSlides(TcMaterial m) {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(storage.get(m.getStorageKey())))) {
            Dimension d = ppt.getPageSize();
            List<String> keys = new ArrayList<>();
            int n = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                if (++n > MAX_SLIDES) {
                    break;
                }
                BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, d.width, d.height);
                slide.draw(g);
                g.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                keys.add(storage.put("preview", "png", out.toByteArray()));
            }
            return keys;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "课件无法预览，请下载后查看");
        }
    }

    private String docxHtml(TcMaterial m) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(storage.get(m.getStorageKey())))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t == null || t.isBlank()) {
                    continue;
                }
                boolean heading = p.getStyle() != null && p.getStyle().toLowerCase().startsWith("heading");
                sb.append(heading ? "<h3>" : "<p>").append(HtmlUtils.htmlEscape(t)).append(heading ? "</h3>" : "</p>");
            }
            return sb.toString();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档无法预览，请下载后查看");
        }
    }
}
