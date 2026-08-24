package com.pritset.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pritset.sdk.exception.PritsetApiException;
import com.pritset.sdk.exception.PritsetException;
import com.pritset.sdk.exception.PritsetTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Blocking client for the Pritset Document API. */
public final class PritsetClient {
    public static final String VERSION = "0.1.0";

    private final String accessToken;
    private final String secret;
    private final URI baseUri;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TemplatesResource templates;
    private final DocumentsResource documents;

    private PritsetClient(Builder builder) {
        validateCredential(builder.accessToken, "accessToken");
        validateCredential(builder.secret, "secret");
        this.baseUri = validateBaseUri(builder.baseUri);
        if (builder.timeout == null || builder.timeout.isZero() || builder.timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        if (builder.httpClient != null && builder.httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Injected HttpClient instances must disable redirects");
        }

        this.accessToken = builder.accessToken;
        this.secret = builder.secret;
        this.timeout = builder.timeout;
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder()
                        .connectTimeout(builder.timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.objectMapper = configureObjectMapper(builder.objectMapper);
        this.templates = new TemplatesResource(this);
        this.documents = new DocumentsResource(this);
    }

    public static Builder builder(String accessToken, String secret) {
        return new Builder(accessToken, secret);
    }

    public TemplatesResource templates() {
        return templates;
    }

    public DocumentsResource documents() {
        return documents;
    }

    @Override
    public String toString() {
        return "PritsetClient(baseUri=" + baseUri + ", credentials=[REDACTED])";
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    <T> T sendJson(
            String method,
            String path,
            Map<String, String> query,
            MultipartBody multipart,
            Class<T> responseType) throws PritsetException {
        HttpResponse<InputStream> response = send(method, path, query, multipart, "application/json");
        requireSuccess(response);
        T value;
        try (InputStream input = response.body()) {
            value = objectMapper.readValue(input, responseType);
        } catch (JsonProcessingException exception) {
            throw new PritsetTransportException("Pritset returned an invalid JSON response.", false, false);
        } catch (IOException exception) {
            throw new PritsetTransportException("The Pritset response could not be read.", false, false);
        }
        if (value == null) {
            throw new PritsetTransportException("Pritset returned an unexpected JSON response.", false, false);
        }
        return value;
    }

    boolean sendBoolean(String method, String path, MultipartBody multipart) throws PritsetException {
        HttpResponse<InputStream> response = send(method, path, Map.of(), multipart, "application/json");
        requireSuccess(response);
        JsonNode value;
        try (InputStream input = response.body()) {
            value = objectMapper.readTree(input);
        } catch (JsonProcessingException exception) {
            throw new PritsetTransportException("Pritset returned an invalid JSON response.", false, false);
        } catch (IOException exception) {
            throw new PritsetTransportException("The Pritset response could not be read.", false, false);
        }
        if (value == null || !value.isBoolean()) {
            throw new PritsetTransportException("Pritset returned an unexpected validation response.", false, false);
        }
        return value.booleanValue();
    }

    void sendEmpty(String method, String path) throws PritsetException {
        HttpResponse<InputStream> response = send(method, path, Map.of(), null, "application/json");
        requireSuccess(response);
        try {
            response.body().close();
        } catch (IOException exception) {
            throw new PritsetTransportException("The Pritset response could not be closed.", false, false);
        }
    }

    BinaryResponse sendBinary(String method, String path, MultipartBody multipart) throws PritsetException {
        HttpResponse<InputStream> response = send(method, path, Map.of(), multipart, "*/*");
        requireSuccess(response);
        return new BinaryResponse(
                response.body(),
                response.headers().firstValue("Content-Type").map(PritsetClient::mediaTypeOnly).orElse(null),
                response.headers().firstValueAsLong("Content-Length").orElse(-1),
                response.headers().firstValue("X-Trace").orElse(null));
    }

    private HttpResponse<InputStream> send(
            String method,
            String path,
            Map<String, String> query,
            MultipartBody multipart,
            String accept) throws PritsetTransportException {
        HttpRequest.BodyPublisher publisher = multipart == null
                ? HttpRequest.BodyPublishers.noBody()
                : multipart.publisher();
        HttpRequest.Builder request = HttpRequest.newBuilder(UriCodec.build(baseUri, path, query))
                .timeout(timeout)
                .header("Authorization", accessToken)
                .header("X-Secret", secret)
                .header("User-Agent", "pritset-java/" + VERSION)
                .header("Accept", accept)
                .method(method, publisher);
        if (multipart != null) {
            request.header("Content-Type", multipart.contentType());
        }

        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new PritsetTransportException("The Pritset request timed out.", true, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PritsetTransportException("The Pritset request was interrupted.", false, true);
        } catch (IOException exception) {
            throw new PritsetTransportException(
                    "The request to Pritset failed before a response was received.", false, false);
        }
    }

    private void requireSuccess(HttpResponse<InputStream> response) throws PritsetApiException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw ErrorParser.parse(response, objectMapper);
        }
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper custom) {
        ObjectMapper mapper = custom == null ? new ObjectMapper() : custom.copy();
        mapper.deactivateDefaultTyping();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private static URI validateBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        if (!value.isAbsolute() || value.getHost() == null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI");
        }
        if (value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must not contain credentials, a query, or a fragment");
        }
        boolean https = value.getScheme().equalsIgnoreCase("https");
        boolean loopback = value.getHost().equalsIgnoreCase("localhost")
                || value.getHost().equals("127.0.0.1")
                || value.getHost().equals("::1");
        boolean loopbackHttp = value.getScheme().equalsIgnoreCase("http") && loopback;
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException("baseUri must use HTTPS; HTTP is allowed only for loopback development");
        }
        return URI.create(value.toString().replaceAll("/+$", ""));
    }

    private static void validateCredential(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must not contain line breaks");
        }
    }

    private static String mediaTypeOnly(String value) {
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator).strip();
    }

    /** Builds immutable Pritset clients. */
    public static final class Builder {
        private final String accessToken;
        private final String secret;
        private URI baseUri = URI.create("https://api.pritset.com");
        private Duration timeout = Duration.ofSeconds(30);
        private HttpClient httpClient;
        private ObjectMapper objectMapper;

        private Builder(String accessToken, String secret) {
            this.accessToken = accessToken;
            this.secret = secret;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Injects a caller-owned client whose redirect policy must be {@link HttpClient.Redirect#NEVER}. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Injects a mapper configuration. The SDK copies it before applying required safety settings. */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public PritsetClient build() {
            return new PritsetClient(this);
        }
    }
}
