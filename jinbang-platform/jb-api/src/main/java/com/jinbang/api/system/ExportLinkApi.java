package com.jinbang.api.system;

import java.time.Duration;

/**
 * 导出文件 / 报告下载链接。链接绑定生成它的身份：身份被撤销（离职、换班、解绑）后立即失效（PRD 8.3、AC-14）。
 */
public interface ExportLinkApi {

    /** @return 下载地址，例如 /api/files/download/{token} */
    String createLink(String storageKey, String fileName, Duration ttl);
}
