package com.jinbang.common.storage;

/**
 * 文件存储抽象。开发环境落本地磁盘；生产换 OSS（STS 直传）或 MinIO（私有化）。
 * 学生作文原图、答卷图片必须放私有桶，只能通过短时下载凭证访问（技术路线 2）。
 */
public interface FileStorage {

    /** @return 存储键，例如 ppt/2026/11/xxx.pptx */
    String put(String folder, String extension, byte[] content);

    byte[] get(String key);

    boolean exists(String key);

    void delete(String key);
}
