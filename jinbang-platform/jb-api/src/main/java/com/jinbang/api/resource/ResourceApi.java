package com.jinbang.api.resource;

/** 备课「找资源」：把平台资源取进教师的备课目录（按资源下载规则校验可见性和付费）。 */
public interface ResourceApi {

    ResourceFile takeForPrep(Long resourceId);

    record ResourceFile(Long resourceId, String title, String fileExt, String storageKey, long size, long paidCoins) {
    }
}
