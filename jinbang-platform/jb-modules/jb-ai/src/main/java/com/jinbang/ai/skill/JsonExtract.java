package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 模型经常在 JSON 前后加说明或 ```json 包裹，这里截取第一个完整的 JSON 再解析。 */
public final class JsonExtract {
    private JsonExtract() {
    }

    public static JsonNode object(ObjectMapper om, String text) {
        return parse(om, text, '{', '}');
    }

    public static JsonNode array(ObjectMapper om, String text) {
        return parse(om, text, '[', ']');
    }

    private static JsonNode parse(ObjectMapper om, String text, char open, char close) {
        if (text == null) {
            return null;
        }
        int s = text.indexOf(open);
        int e = text.lastIndexOf(close);
        if (s < 0 || e <= s) {
            return null;
        }
        try {
            return om.readTree(text.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
