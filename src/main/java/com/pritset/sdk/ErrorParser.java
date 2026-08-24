package com.pritset.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pritset.sdk.exception.PritsetApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ErrorParser {
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private ErrorParser() {}

    static PritsetApiException parse(HttpResponse<InputStream> response, ObjectMapper objectMapper) {
        String body;
        try (InputStream input = response.body()) {
            body = new String(input.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            body = "";
        }

        String message = "Pritset API request failed with status " + response.statusCode() + ".";
        String traceId = header(response, "X-Trace");
        Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.isObject()) {
                if (root.path("title").isTextual()) {
                    message = root.path("title").textValue();
                } else if (root.path("message").isTextual()) {
                    message = root.path("message").textValue();
                }
                if (root.path("traceId").isTextual()) {
                    traceId = root.path("traceId").textValue();
                }

                JsonNode source = root.path("errors").isObject() ? root.path("errors") : root;
                Iterator<Map.Entry<String, JsonNode>> fields = source.properties().iterator();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (isMetadata(field.getKey())) {
                        continue;
                    }
                    List<String> messages = stringMessages(field.getValue());
                    if (!messages.isEmpty()) {
                        fieldErrors.put(field.getKey(), messages);
                    }
                }
            }
        } catch (JsonProcessingException exception) {
            if (!body.isBlank()) {
                message = body.strip();
            }
        }

        return new PritsetApiException(
                message,
                response.statusCode(),
                fieldErrors,
                traceId,
                header(response, "Retry-After"),
                body);
    }

    private static List<String> stringMessages(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.textValue());
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                messages.add(item.textValue());
            }
        }
        return List.copyOf(messages);
    }

    private static boolean isMetadata(String name) {
        return name.equals("type")
                || name.equals("title")
                || name.equals("message")
                || name.equals("status")
                || name.equals("traceId");
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
