package com.jinbang.system.auth;

/** 短信通道。生产接阿里云短信；开发环境默认只写日志。 */
public interface SmsSender {
    void sendCode(String phone, String scene, String code);
}
