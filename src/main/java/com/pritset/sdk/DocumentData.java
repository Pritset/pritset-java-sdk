package com.pritset.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class DocumentData {
    private DocumentData() {}

    static String serialize(Object data, ObjectMapper objectMapper) {
        try {
            if (data instanceof String rawJson) {
                JsonNode node = objectMapper.readTree(rawJson);
                if (node == null) {
                    throw new IllegalArgumentException("Document data must contain one JSON value");
                }
                return rawJson;
            }
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Document data must be valid JSON", exception);
        }
    }
}
