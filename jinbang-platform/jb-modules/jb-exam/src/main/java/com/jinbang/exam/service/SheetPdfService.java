package com.jinbang.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.exam.domain.ExSheet;
import com.jinbang.exam.domain.ExSheetVersion;
import com.jinbang.exam.mapper.ExSheetVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 答题卡 PDF 标准件（前端方案 4.1、7.3）：浏览器打印的位置精度不可靠，打印一律用这里生成的 PDF。
 * 版面：四角定位点（缺一仍可识别）、每页二维码 JBSHEET:{sheetId}:{version}、卡头、手写学号格、
 * 客观题括号、主观题作答区 + 右上角得分框、作文格。每道题的区域坐标写回版本记录，供识别服务切图。
 * 一张 A4 只印一份答题卡（PRD 6.3 打印规则），题多时分页，每页带同一个二维码和页码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SheetPdfService {
    private static final float MARGIN = 40;
    private static final float MARK = 14;
    private static final List<String> FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/msyh.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/System/Library/Fonts/PingFang.ttc");

    private final ExSheetVersionMapper versionMapper;
    private final ObjectMapper om;

    @Value("${jb.pdf.font-path:}")
    private String fontPath;

    public record Region(int page, String no, String type, float x, float y, float w, float h) {
    }

    public record Rendered(byte[] pdf, List<Region> regions, int pages) {
    }

    public Rendered render(ExSheet sheet, ExSheetVersion v, List<SheetService.Block> blocks) {
        PDRectangle size = "A3".equalsIgnoreCase(sheet.getPaperSize()) ? PDRectangle.A3 : PDRectangle.A4;
        String qr = "JBSHEET:" + sheet.getId() + ":" + v.getVersionNo();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadFont(doc);
            PDImageXObject qrImg = LosslessFactory.createFromImage(doc, qrImage(qr));
            Ctx c = new Ctx(doc, font, qrImg, size, sheet.getSheetName(), v.getVersionNo());
            c.newPage();
            header(c, sheet);
            List<SheetService.Block> objectives = blocks.stream().filter(SheetService.Block::objective).toList();
            objectiveSection(c, objectives);
            for (SheetService.Block b : blocks) {
                if (!b.objective()) {
                    subjective(c, b);
                }
            }
            c.close();
            doc.save(out);
            v.setRegionsJson(om.writeValueAsString(c.regions));
            versionMapper.updateById(v);
            return new Rendered(out.toByteArray(), c.regions, c.pageNo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------- 版面 ----------------

    private void header(Ctx c, ExSheet sheet) throws IOException {
        c.text(sheet.getSheetName() + " 答题卡", MARGIN, c.y, 18);
        c.y -= 30;
        c.text("学校：____________  姓名：__________  班级：__________  考场：________", MARGIN, c.y, 11);
        c.y -= 26;
        int digits = sheet.getStudentNoDigits() == null ? 6 : sheet.getStudentNoDigits();
        c.text("学号（每格写一位数字，居中不压框）：", MARGIN, c.y, 10);
        float bx = MARGIN + 190;
        float bw = 22;
        float bh = 26;
        for (int i = 0; i < digits; i++) {
            c.rect(bx + i * (bw + 4), c.y - 8, bw, bh, false);
        }
        c.region("STUDENT_NO", "STUDENT_NO", bx, c.y - 8, digits * (bw + 4), bh);
        c.y -= 34;
        c.text("注意：1. 客观题在括号内书写字母，勿打勾；2. 主观题勿写入得分框；3. 保持四角定位点和二维码完好。", MARGIN, c.y, 8);
        c.y -= 18;
    }

    private void objectiveSection(Ctx c, List<SheetService.Block> items) throws IOException {
        if (items.isEmpty()) {
            return;
        }
        c.ensure(40);
        c.text("一、选择题 / 判断题", MARGIN, c.y, 12);
        c.y -= 22;
        float cellW = 96;
        int perRow = (int) ((c.size.getWidth() - 2 * MARGIN) / cellW);
        for (int i = 0; i < items.size(); i += perRow) {
            c.ensure(28);
            for (int j = 0; j < perRow && i + j < items.size(); j++) {
                SheetService.Block b = items.get(i + j);
                float x = MARGIN + j * cellW;
                c.text(b.no() + ".", x, c.y, 10);
                c.rect(x + 24, c.y - 6, 52, 20, false);
                c.region(b.no(), b.type(), x + 24, c.y - 6, 52, 20);
            }
            c.y -= 28;
        }
        c.y -= 6;
    }

    private void subjective(Ctx c, SheetService.Block b) throws IOException {
        boolean essay = "ESSAY".equals(b.type());
        float width = c.size.getWidth() - 2 * MARGIN;
        float height;
        if (essay) {
            int words = b.minWords() == null ? 800 : Math.max(b.minWords() + 100, 200);
            int rows = (int) Math.ceil(words / 20.0);
            height = Math.min(rows * (width / 20), c.size.getHeight() - 2 * MARGIN - 60);
        } else {
            height = Math.max(90, Math.min(320, 50 + b.fullScore().floatValue() * 12));
        }
        c.ensure(height + 24);
        c.text(b.no() + ".（" + b.fullScore().stripTrailingZeros().toPlainString() + " 分）", MARGIN, c.y, 11);
        c.y -= 8;
        float top = c.y;
        c.rect(MARGIN, top - height, width, height, false);
        c.region(b.no(), b.type(), MARGIN, top - height, width, height);
        // 得分框（先阅后扫：教师用红笔写分）
        c.rect(MARGIN + width - 64, top - 26, 64, 26, false);
        c.text("得分", MARGIN + width - 62, top - 10, 7);
        c.region(b.no() + "#score", "SCORE_BOX", MARGIN + width - 64, top - 26, 64, 26);
        if (essay) {
            float cell = width / 20;
            int rows = (int) (height / cell);
            for (int r = 1; r < rows; r++) {
                c.line(MARGIN, top - r * cell, MARGIN + width, top - r * cell);
            }
            for (int k = 1; k < 20; k++) {
                c.line(MARGIN + k * cell, top - rows * cell, MARGIN + k * cell, top - 26);
            }
        }
        c.y = top - height - 16;
    }

    // ---------------- 工具 ----------------

    private PDFont loadFont(PDDocument doc) throws IOException {
        List<String> paths = new ArrayList<>();
        if (fontPath != null && !fontPath.isBlank()) {
            paths.add(fontPath);
        }
        paths.addAll(FONT_CANDIDATES);
        for (String p : paths) {
            File f = new File(p);
            if (!f.isFile()) {
                continue;
            }
            if (p.toLowerCase().endsWith(".ttc")) {
                TrueTypeCollection ttc = new TrueTypeCollection(f);
                TrueTypeFont[] first = new TrueTypeFont[1];
                ttc.processAllFonts(t -> {
                    if (first[0] == null) {
                        first[0] = t;
                    }
                });
                if (first[0] != null) {
                    return PDType0Font.load(doc, first[0], true);
                }
            } else {
                return PDType0Font.load(doc, f);
            }
        }
        throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "服务器缺少中文字体，无法生成答题卡 PDF（请配置 jb.pdf.font-path）");
    }

    private static BufferedImage qrImage(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++) {
                for (int y = 0; y < m.getHeight(); y++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            throw new IllegalStateException("二维码生成失败", e);
        }
    }

    /** 排版上下文：PDF 坐标原点在左下角，区域坐标对外换算成左上角原点。 */
    private static final class Ctx {
        final PDDocument doc;
        final PDFont font;
        final PDImageXObject qr;
        final PDRectangle size;
        final String title;
        final int version;
        final List<Region> regions = new ArrayList<>();
        PDPageContentStream cs;
        int pageNo;
        float y;

        Ctx(PDDocument doc, PDFont font, PDImageXObject qr, PDRectangle size, String title, int version) {
            this.doc = doc;
            this.font = font;
            this.qr = qr;
            this.size = size;
            this.title = title;
            this.version = version;
        }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(size);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNo++;
            float w = size.getWidth();
            float h = size.getHeight();
            for (float[] p : new float[][]{{16, 16}, {w - 16 - MARK, 16}, {16, h - 16 - MARK}, {w - 16 - MARK, h - 16 - MARK}}) {
                rect(p[0], p[1], MARK, MARK, true);
            }
            cs.drawImage(qr, w - MARGIN - 70, h - MARGIN - 70, 70, 70);
            text("v" + version + "  第 " + pageNo + " 页", w - MARGIN - 70, h - MARGIN - 80, 7);
            y = h - MARGIN - 10;
        }

        void ensure(float need) throws IOException {
            if (y - need < MARGIN + 10) {
                newPage();
            }
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        void text(String s, float x, float yy, float fs) throws IOException {
            cs.beginText();
            cs.setFont(font, fs);
            cs.newLineAtOffset(x, yy);
            cs.showText(s);
            cs.endText();
        }

        void rect(float x, float yy, float w, float h, boolean fill) throws IOException {
            cs.addRect(x, yy, w, h);
            if (fill) {
                cs.fill();
            } else {
                cs.setLineWidth(0.8f);
                cs.stroke();
            }
        }

        void line(float x1, float y1, float x2, float y2) throws IOException {
            cs.setLineWidth(0.3f);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        void region(String no, String type, float x, float yBottom, float w, float h) {
            regions.add(new Region(pageNo, no, type, x, size.getHeight() - (yBottom + h), w, h));
        }
    }
}
