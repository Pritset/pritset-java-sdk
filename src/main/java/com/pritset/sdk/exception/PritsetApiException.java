package com.pritset.sdk.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A non-success response returned by the Pritset API. */
public final class PritsetApiException extends PritsetException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final LinkedHashMap<String, ArrayList<String>> fieldErrors;
    private final String traceId;
    private final String retryAfter;
    private final String responseBody;

    public PritsetApiException(
            String message,
            int statusCode,
            Map<String, List<String>> fieldErrors,
            String traceId,
            String retryAfter,
            String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.fieldErrors = new LinkedHashMap<>();
        fieldErrors.forEach((name, messages) -> this.fieldErrors.put(name, new ArrayList<>(messages)));
        this.traceId = traceId;
        this.retryAfter = retryAfter;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> fieldErrors() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        fieldErrors.forEach((name, messages) -> result.put(name, List.copyOf(messages)));
        return Collections.unmodifiableMap(result);
    }

    public Optional<String> traceId() {
        return Optional.ofNullable(traceId);
    }

    public Optional<String> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** Returns the capped response body retained for diagnostics. */
    public String responseBody() {
        return responseBody;
    }
}
