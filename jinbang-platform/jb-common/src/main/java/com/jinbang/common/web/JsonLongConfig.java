package com.jinbang.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/**
 * Long 精度保护：雪花 ID 是 19 位整数，超过 JavaScript 能精确表示的 2^53，浏览器解析后末几位会被改掉。
 * <ul>
 *   <li>名为 id 或以 Id 结尾的 Long 属性一律输出字符串（前端类型同步为 string，见 jb-app 的 OpenAPI 定制）</li>
 *   <li>其他 Long（列表元素、计数、金额）：超出安全范围才输出字符串，否则仍是数字</li>
 * </ul>
 * 反序列化不受影响：字符串和数字都能读成 Long。
 */
@Configuration
public class JsonLongConfig {
    static final long JS_MAX_SAFE = 9007199254740991L;

    static final class SafeLongSerializer extends JsonSerializer<Long> {
        @Override
        public void serialize(Long v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v > JS_MAX_SAFE || v < -JS_MAX_SAFE) {
                g.writeString(v.toString());
            } else {
                g.writeNumber(v);
            }
        }
    }

    public static boolean isIdName(String name) {
        return "id".equals(name) || name.endsWith("Id");
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("jb-long");
            module.addSerializer(Long.class, new SafeLongSerializer());
            module.addSerializer(Long.TYPE, new SafeLongSerializer());
            module.setSerializerModifier(new BeanSerializerModifier() {
                @Override
                public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription desc,
                                                                 List<BeanPropertyWriter> props) {
                    for (BeanPropertyWriter w : props) {
                        Class<?> raw = w.getType().getRawClass();
                        if ((raw == Long.class || raw == Long.TYPE) && isIdName(w.getName())) {
                            w.assignSerializer(ToStringSerializer.instance);
                        }
                    }
                    return props;
                }
            });
            builder.modulesToInstall(module);
        };
    }
}
