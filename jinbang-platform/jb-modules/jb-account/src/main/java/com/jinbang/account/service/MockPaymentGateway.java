package com.jinbang.account.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 模拟支付通道：签名 = HMAC-SHA256(orderNo|channelTxn, secret)。仅用于开发和测试。 */
@Component
@ConditionalOnProperty(name = "jb.pay.channel", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {
    private final String secret;

    public MockPaymentGateway(@Value("${jb.pay.mock-secret:mock-secret}") String secret) {
        this.secret = secret;
    }

    @Override
    public String prepay(String orderNo, long amountFen, String subject) {
        return "mockpay://pay?orderNo=" + orderNo + "&amount=" + amountFen;
    }

    @Override
    public boolean verifyNotify(String orderNo, String channelTxn, String sign) {
        return sign != null && sign.equals(sign(orderNo, channelTxn));
    }

    @Override
    public String channel() {
        return "MOCK";
    }

    public String sign(String orderNo, String channelTxn) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((orderNo + "|" + channelTxn).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
