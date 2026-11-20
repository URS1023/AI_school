package com.jinbang.system.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 算术图形验证码（现网是 {@code 3*4=?}）。一次性，2 分钟有效。
 * PRD 2.1 指出算术验证码强度低，登录失败锁定是主防线；后续可换滑块/行为验证码。
 */
@Service
public class CaptchaService {
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, String> answers = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2)).maximumSize(200_000).build();

    public record Captcha(String captchaId, String imageBase64) {
    }

    public Captcha create() {
        int a = random.nextInt(1, 10);
        int b = random.nextInt(1, 10);
        String expr;
        int result;
        switch (random.nextInt(3)) {
            case 0 -> {
                expr = a + "+" + b;
                result = a + b;
            }
            case 1 -> {
                int hi = Math.max(a, b);
                int lo = Math.min(a, b);
                expr = hi + "-" + lo;
                result = hi - lo;
            }
            default -> {
                expr = a + "*" + b;
                result = a * b;
            }
        }
        String id = UUID.randomUUID().toString();
        answers.put(id, String.valueOf(result));
        return new Captcha(id, render(expr + "=?"));
    }

    /** 校验并作废（无论对错都只能用一次）。 */
    public void verify(String captchaId, String code) {
        String expected = captchaId == null ? null : answers.asMap().remove(captchaId);
        if (expected == null || code == null || !expected.equals(code.trim())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /** 测试与内部工具使用：直接取答案而不作废。 */
    String peek(String captchaId) {
        return answers.getIfPresent(captchaId);
    }

    private String render(String text) {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, 120, 40);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(random.nextInt(150, 230), random.nextInt(150, 230), random.nextInt(150, 230)));
            g.drawLine(random.nextInt(120), random.nextInt(40), random.nextInt(120), random.nextInt(40));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        g.setColor(new Color(40, 60, 120));
        g.drawString(text, 10, 29);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
