package com.java_template.common.util.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ABOUTME: Composite parser that delegates to specific parsers based on the Content-Type header.
 * Provides a flexible way to handle multiple content types with appropriate parsing strategies.
 */
public class ContentTypeAwareParser implements ResponseBodyParser {
    private final Map<String, ResponseBodyParser> parsersByContentType;
    private final ResponseBodyParser defaultParser;

    private ContentTypeAwareParser(Map<String, ResponseBodyParser> parsersByContentType,
                                   ResponseBodyParser defaultParser) {
        this.parsersByContentType = parsersByContentType;
        this.defaultParser = defaultParser;
    }

    @Override
    public ObjectNode parse(String body, String contentType, int statusCode) throws ResponseParsingException {
        ResponseBodyParser parser = selectParser(contentType);
        return parser.parse(body, contentType, statusCode);
    }

    private ResponseBodyParser selectParser(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return defaultParser;
        }

        // Extract base content type (ignore charset and other parameters)
        String baseContentType = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);

        return parsersByContentType.getOrDefault(baseContentType, defaultParser);
    }

    /**
     * Builder for creating ContentTypeAwareParser with custom parser mappings.
     */
    public static class Builder {
        private final Map<String, ResponseBodyParser> parsers = new HashMap<>();
        private ResponseBodyParser defaultParser;
        private final ObjectMapper objectMapper;

        public Builder(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            // Set sensible defaults
            this.defaultParser = new JsonResponseParser(objectMapper, JsonParsingMode.LENIENT);
        }

        public Builder withParser(String contentType, ResponseBodyParser parser) {
            parsers.put(contentType.toLowerCase(Locale.ROOT), parser);
            return this;
        }

        public Builder withJsonParser(JsonParsingMode mode) {
            parsers.put("application/json", new JsonResponseParser(objectMapper, mode));
            return this;
        }

        public Builder withPlainTextParser() {
            parsers.put("text/plain", new PlainTextResponseParser(objectMapper));
            return this;
        }

        public Builder withDefaultParser(ResponseBodyParser parser) {
            this.defaultParser = parser;
            return this;
        }

        public ContentTypeAwareParser build() {
            return new ContentTypeAwareParser(new HashMap<>(parsers), defaultParser);
        }
    }

    /**
     * Creates a default parser with lenient JSON parsing for application/json
     * and text parsing for text/plain.
     */
    public static ContentTypeAwareParser createDefault(ObjectMapper objectMapper) {
        return new Builder(objectMapper)
                .withJsonParser(JsonParsingMode.LENIENT)
                .withPlainTextParser()
                .build();
    }

    /**
     * Creates a strict parser that fails fast on malformed JSON.
     */
    public static ContentTypeAwareParser createStrict(ObjectMapper objectMapper) {
        return new Builder(objectMapper)
                .withJsonParser(JsonParsingMode.STRICT)
                .withPlainTextParser()
                .withDefaultParser(new JsonResponseParser(objectMapper, JsonParsingMode.STRICT))
                .build();
    }
}

