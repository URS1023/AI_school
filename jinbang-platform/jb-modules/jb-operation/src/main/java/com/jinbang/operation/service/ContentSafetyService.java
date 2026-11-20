package com.jinbang.operation.service;

import com.jinbang.api.operation.ContentSafetyApi;
import com.jinbang.operation.domain.OpSensitiveWord;
import com.jinbang.operation.mapper.OpSensitiveWordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 敏感词过滤：词库缓存 5 分钟。词多了以后换 AC 自动机，接口不变。 */
@Service
@RequiredArgsConstructor
public class ContentSafetyService implements ContentSafetyApi {
    private final OpSensitiveWordMapper wordMapper;
    private volatile List<String> words = List.of();
    private volatile long loadedAt;

    @Override
    public SafetyResult check(String text) {
        if (text == null || text.isBlank()) {
            return SafetyResult.pass();
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}　]", "");
        List<String> hits = new ArrayList<>();
        for (String w : words()) {
            if (normalized.contains(w)) {
                hits.add(w);
            }
        }
        return new SafetyResult(!hits.isEmpty(), hits);
    }

    public void addWord(String word) {
        OpSensitiveWord w = new OpSensitiveWord();
        w.setWord(word.trim().toLowerCase(Locale.ROOT));
        wordMapper.insert(w);
        loadedAt = 0;
    }

    private List<String> words() {
        if (System.currentTimeMillis() - loadedAt > 300_000) {
            words = wordMapper.selectList(null).stream().map(OpSensitiveWord::getWord).toList();
            loadedAt = System.currentTimeMillis();
        }
        return words;
    }
}
