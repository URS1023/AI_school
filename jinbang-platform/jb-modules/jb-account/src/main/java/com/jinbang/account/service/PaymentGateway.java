package com.jinbang.account.service;

/**
 * 支付通道。生产接微信支付（WxJava，Native/JSAPI/H5）；开发环境用模拟通道。
 */
public interface PaymentGateway {

    /** 下单，返回给前端的支付参数（二维码链接或 JSAPI 参数）。 */
    String prepay(String orderNo, long amountFen, String subject);

    /** 校验支付回调签名。 */
    boolean verifyNotify(String orderNo, String channelTxn, String sign);

    String channel();
}
