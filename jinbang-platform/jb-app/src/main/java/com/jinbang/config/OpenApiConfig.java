package com.jinbang.config;

import com.jinbang.common.web.JsonLongConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * OpenAPI 文档与 JSON 实际输出保持一致（前端据此生成 TypeScript 类型）：
 * id / xxxId 属性、路径和查询参数里的 id、以及 Long 列表，都标成 string（见 {@link JsonLongConfig}）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenApiCustomizer idAsString() {
        return api -> {
            api.info(new Info().title("世纪金榜 AI 教学平台 API").version("1.0"));
            if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
                api.getComponents().getSchemas().values().forEach(OpenApiConfig::fixSchema);
            }
            if (api.getPaths() != null) {
                api.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
                    if (op.getParameters() != null) {
                        for (Parameter p : op.getParameters()) {
                            if (p.getSchema() != null && isInt64(p.getSchema()) && JsonLongConfig.isIdName(p.getName())) {
                                toString(p.getSchema());
                            } else if (p.getSchema() != null && isInt64Array(p.getSchema())) {
                                toString(p.getSchema().getItems());
                            }
                        }
                    }
                }));
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fixSchema(Schema schema) {
        Map<String, Schema> props = schema.getProperties();
        if (props == null) {
            return;
        }
        props.forEach((name, s) -> {
            if (isInt64(s) && JsonLongConfig.isIdName(name)) {
                toString(s);
            } else if (isInt64Array(s)) {
                toString(s.getItems());
            }
        });
    }

    /** 兼容 OpenAPI 3.0（type）与 3.1（types 集合）两种写法。 */
    private static boolean is(Schema<?> s, String type) {
        return type.equals(s.getType()) || (s.getTypes() != null && s.getTypes().contains(type));
    }

    private static boolean isInt64(Schema<?> s) {
        return is(s, "integer") && "int64".equals(s.getFormat());
    }

    private static boolean isInt64Array(Schema<?> s) {
        return is(s, "array") && s.getItems() != null && isInt64(s.getItems());
    }

    private static void toString(Schema<?> s) {
        s.setType("string");
        if (s.getTypes() != null) {
            s.setTypes(new java.util.LinkedHashSet<>(java.util.List.of("string")));
        }
        s.setFormat(null);
    }

    @SuppressWarnings("unused")
    private static OpenAPI noop(OpenAPI a) {
        return a;
    }
}
