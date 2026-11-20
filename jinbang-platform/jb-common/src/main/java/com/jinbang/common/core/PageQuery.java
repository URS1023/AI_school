package com.jinbang.common.core;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

@Data
public class PageQuery {
    private long pageNum = 1;
    private long pageSize = 20;

    public <T> Page<T> toPage() {
        return new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 200));
    }
}
