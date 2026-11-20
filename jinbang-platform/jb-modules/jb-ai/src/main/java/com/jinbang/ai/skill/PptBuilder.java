package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** 用 Apache POI 按大纲生成 PPTX（技术路线 2：统一 PPT 引擎）。模板决定配色。 */
public final class PptBuilder {
    public record Theme(String name, Color background, Color title, Color text, Color accent) {
    }

    public static final Map<String, Theme> THEMES = Map.of(
            "urban_blue", new Theme("都市蓝调", new Color(240, 245, 252), new Color(22, 64, 140), new Color(40, 40, 40), new Color(52, 120, 220)),
            "deep_calm", new Theme("深邃沉稳", new Color(28, 34, 48), new Color(240, 200, 120), new Color(230, 230, 230), new Color(240, 200, 120)),
            "fresh_light", new Theme("浅蓝小清新", new Color(236, 248, 252), new Color(30, 120, 150), new Color(50, 60, 70), new Color(90, 180, 200)),
            "simple_green", new Theme("简约绿意", new Color(244, 250, 242), new Color(40, 110, 60), new Color(40, 50, 40), new Color(90, 170, 100)),
            "red_china", new Theme("山河映红", new Color(253, 246, 240), new Color(170, 30, 30), new Color(60, 40, 30), new Color(200, 60, 40)));

    private PptBuilder() {
    }

    public record Built(byte[] content, int pages) {
    }

    public static Built build(JsonNode outline, String themeCode) {
        Theme theme = THEMES.getOrDefault(themeCode, THEMES.get("urban_blue"));
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(960, 540));
            cover(ppt, theme, outline.path("title").asText("课件"), "内容由 AI 生成，仅供参考");
            for (JsonNode ch : outline.path("chapters")) {
                cover(ppt, theme, ch.path("title").asText(), null);
                for (JsonNode sec : ch.path("sections")) {
                    XSLFSlide s = blank(ppt, theme);
                    text(s, sec.path("title").asText(), 40, 30, 880, 60, 30, theme.title(), true);
                    XSLFTextBox body = s.createTextBox();
                    body.setAnchor(new Rectangle2D.Double(60, 110, 840, 380));
                    body.clearText();
                    for (JsonNode pt : sec.path("points")) {
                        XSLFTextParagraph para = body.addNewTextParagraph();
                        para.setBullet(true);
                        XSLFTextRun r = para.addNewTextRun();
                        r.setText(pt.asText());
                        r.setFontSize(22.0);
                        r.setFontColor(theme.text());
                    }
                }
            }
            ppt.write(out);
            return new Built(out.toByteArray(), ppt.getSlides().size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static XSLFSlide blank(XMLSlideShow ppt, Theme theme) {
        XSLFSlide s = ppt.createSlide(ppt.getSlideMasters().getFirst().getLayout(SlideLayout.BLANK));
        s.getBackground().setFillColor(theme.background());
        return s;
    }

    private static void cover(XMLSlideShow ppt, Theme theme, String title, String sub) {
        XSLFSlide s = blank(ppt, theme);
        text(s, title, 80, 190, 800, 90, 40, theme.title(), true);
        if (sub != null) {
            text(s, sub, 80, 300, 800, 40, 16, theme.accent(), false);
        }
    }

    private static void text(XSLFSlide s, String t, double x, double y, double w, double h, double size, Color c,
                             boolean bold) {
        XSLFTextBox box = s.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        XSLFTextRun r = box.addNewTextParagraph().addNewTextRun();
        r.setText(t);
        r.setFontSize(size);
        r.setFontColor(c);
        r.setBold(bold);
    }
}
