package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.boot.convert.DurationStyle;

import java.io.IOException;
import java.time.Duration;

/**
 * Duration 反序列化：兼容 Spring Boot 简写（如 {@code 500ms}、{@code 1m}、{@code 30s}）
 * 与标准 ISO-8601（{@code PT0.5S}）；数字按毫秒处理（便于测试构造）。
 */
public class SimpleDurationDeserializer extends JsonDeserializer<Duration> {

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        if (text.startsWith("P") || text.startsWith("p")) {
            return Duration.parse(text);
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return Duration.ofMillis(Long.parseLong(text));
        }
        return DurationStyle.detect(text).parse(text);
    }
}
