package com.pritset.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.pritset.sdk.exception.PritsetApiException;
import com.pritset.sdk.exception.PritsetTransportException;
import com.pritset.sdk.model.CreateTemplateRequest;
import com.pritset.sdk.model.ListTemplatesOptions;
import com.pritset.sdk.model.SortDirection;
import com.pritset.sdk.model.Template;
import com.pritset.sdk.model.TemplateDetails;
import com.pritset.sdk.model.TemplatePage;
import com.pritset.sdk.model.UpdateTemplateRequest;
import com.pritset.sdk.model.WebhookJob;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PritsetClientTest {
    private HttpServer server;
    private URI baseUri;
    private final Deque<MockResponse> responses = new ArrayDeque<>();
    private final Deque<RecordedRequest> requests = new ArrayDeque<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void listsTemplatesUsingContractFixtureAndAuthenticationHeaders() throws Exception {
        enqueueJson(200, fixture("templates/list.json"));
        PritsetClient client = client();

        TemplatePage page = client.templates().list(ListTemplatesOptions.builder()
                .query("monthly invoice")
                .page(2)
                .pageSize(25)
                .sortBy("name")
                .sortDirection(SortDirection.ASCENDING)
                .build());

        assertEquals(1, page.total());
        assertEquals("Monthly invoice", page.data().get(0).name());
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.method());
        assertEquals("/v1/api/template", request.rawPath());
        assertTrue(request.rawQuery().contains("q=monthly%20invoice"));
        assertTrue(request.rawQuery().contains("p=2"));
        assertTrue(request.rawQuery().contains("s=25"));
        assertTrue(request.rawQuery().contains("sorts%5B0%5D.sortBy=name"));
        assertTrue(request.rawQuery().contains("sorts%5B0%5D.sortDirection=0"));
        assertEquals("access-token", request.header("Authorization"));
        assertEquals("client-secret", request.header("X-Secret"));
        assertEquals("pritset-java/0.1.5", request.header("User-Agent"));
    }

    @Test
    void getsTemplateWithEscapedIdAndTypedTimestamp() throws Exception {
        enqueueJson(200, fixture("templates/get.json"));

        TemplateDetails details = client().templates().get("a/b");

        assertEquals("a1b2c3d4e5f6", details.template().id());
        assertEquals(24576, details.fileInfo().size());
        assertEquals(Instant.parse("2026-07-15T09:30:00Z"), details.fileInfo().lastModified());
        assertEquals("/v1/api/template/a%2Fb", takeRequest().rawPath());
    }

    @Test
    void createsAndUpdatesTemplatesWithStreamedMultipartBodies() throws Exception {
        enqueueJson(200, "{\"id\":\"new\",\"name\":\"Invoice\",\"tags\":\"billing\"}");
        enqueueJson(200, "{\"id\":\"new\",\"name\":\"Invoice v2\",\"tags\":null}");
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes("docx-bytes"));
        Upload upload = Upload.fromInputStream(
                stream,
                "invoice.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                true);

        Template created = client().templates().create(new CreateTemplateRequest("Invoice", "billing", upload));
        Template updated = client().templates().update("new", new UpdateTemplateRequest("Invoice v2", null, null));

        assertEquals("new", created.id());
        assertEquals("Invoice v2", updated.name());
        assertEquals(0, stream.available());
        RecordedRequest create = takeRequest();
        assertEquals("POST", create.method());
        assertTrue(create.body().contains("name=\"name\""));
        assertTrue(create.body().contains("Invoice"));
        assertTrue(create.body().contains("filename=\"invoice.docx\""));
        assertTrue(create.body().contains("docx-bytes"));
        RecordedRequest update = takeRequest();
        assertEquals("PUT", update.method());
        assertTrue(update.body().contains("Invoice v2"));
        assertFalse(update.body().contains("filename="));
    }

    @Test
    void validatesTemplateAndAcceptsRawJson() throws Exception {
        enqueueJson(200, "true");
        Upload upload = Upload.fromInputStream(new ByteArrayInputStream(bytes("docx")), "template.docx");

        boolean valid = client().templates().validate(upload, "{\"invoice\":{\"number\":42}}");

        assertTrue(valid);
        RecordedRequest request = takeRequest();
        assertEquals("/v1/api/template/process/validate", request.rawPath());
        assertTrue(request.body().contains("name=\"file\"; filename=\"template.docx\""));
        assertTrue(request.body().contains("{\"invoice\":{\"number\":42}}"));
    }

    @Test
    void streamsGeneratedAndDownloadedFilesWithMetadata() throws Exception {
        enqueue(new MockResponse(200, bytes("%PDF-test"), Map.of(
                "Content-Type", "application/pdf; charset=binary",
                "X-Trace", "timing")));
        enqueue(new MockResponse(200, bytes("docx"), Map.of("Content-Type", "application/vnd.test")));
        PritsetClient client = client();

        try (BinaryResponse pdf = client.documents().generate("template", Map.of("name", "Ada"))) {
            assertArrayEquals(bytes("%PDF-test"), pdf.readAllBytes());
            assertEquals("application/pdf", pdf.contentType().orElseThrow());
            assertEquals("timing", pdf.trace().orElseThrow());
        }
        try (BinaryResponse docx = client.templates().download("template")) {
            assertArrayEquals(bytes("docx"), docx.readAllBytes());
        }

        assertEquals("*/*", takeRequest().header("Accept"));
        assertEquals("/v1/api/template/download/template", takeRequest().rawPath());
    }

    @Test
    void supportsPathUploadsAndSavingResponsesToFiles() throws Exception {
        Path input = Files.createTempFile("pritset-template-", ".docx");
        Path output = Files.createTempFile("pritset-document-", ".pdf");
        try {
            Files.write(input, bytes("path-docx"));
            enqueueJson(200, "{\"id\":\"new\",\"name\":\"Path template\"}");
            enqueue(new MockResponse(200, bytes("%PDF-path"), Map.of("Content-Type", "application/pdf")));
            PritsetClient client = client();

            client.templates().create(new CreateTemplateRequest(
                    "Path template",
                    null,
                    Upload.fromPath(input, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")));
            try (BinaryResponse response = client.documents().generate("new", Map.of())) {
                response.save(output);
            }

            assertTrue(takeRequest().body().contains("path-docx"));
            takeRequest();
            assertArrayEquals(bytes("%PDF-path"), Files.readAllBytes(output));
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void startsWebhookJobWithoutFetchingWebhookAndRejectsCredentialUris() throws Exception {
        enqueueJson(200, fixture("documents/webhook-job.json"));
        PritsetClient client = client();

        WebhookJob job = client.documents().generateWebhook(
                "template",
                Map.of("name", "Ada"),
                URI.create("https://example.com/hooks/pritset"));

        assertEquals("57056f7462084dde8902421e9287ea2d", job.id());
        assertTrue(takeRequest().body().contains("https://example.com/hooks/pritset"));
        assertThrows(IllegalArgumentException.class, () -> client.documents().generateWebhook(
                "template",
                Map.of(),
                URI.create("https://user:pass@example.com/hook")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void normalizesContractErrorFixtures() throws Exception {
        enqueue(new MockResponse(400, bytes(fixture("errors/validation-problem.json")), Map.of(
                "Content-Type", "application/problem+json",
                "Retry-After", "10")));
        enqueueJson(422, fixture("errors/field-errors.json"));
        enqueue(new MockResponse(502, bytes(fixture("errors/plain-text.txt")), Map.of()));
        PritsetClient client = client();

        PritsetApiException validation = assertThrows(PritsetApiException.class, client.templates()::list);
        assertEquals(400, validation.statusCode());
        assertEquals("The Name field is required.", validation.fieldErrors().get("Name").get(0));
        assertEquals("00-example-trace-id-00", validation.traceId().orElseThrow());
        assertEquals("10", validation.retryAfter().orElseThrow());

        PritsetApiException field = assertThrows(PritsetApiException.class, client.templates()::list);
        assertEquals("Data is required", field.fieldErrors().get("Data").get(0));

        PritsetApiException plain = assertThrows(PritsetApiException.class, client.templates()::list);
        assertEquals("Template with id a1b2c3d4e5f6 not found", plain.getMessage());
    }

    @Test
    void doesNotFollowRedirectsOrRetryApiErrors() {
        enqueue(new MockResponse(302, new byte[0], Map.of("Location", baseUri + "/sink")));
        PritsetApiException exception = assertThrows(PritsetApiException.class, client().templates()::list);

        assertEquals(302, exception.statusCode());
        assertEquals(1, requests.size());
    }

    @Test
    void sanitizesTransportFailures() {
        server.stop(0);
        PritsetClient unavailable = PritsetClient.builder("access-token", "client-secret")
                .baseUri(baseUri)
                .timeout(Duration.ofMillis(100))
                .build();

        PritsetTransportException exception = assertThrows(
                PritsetTransportException.class,
                unavailable.templates()::list);

        assertFalse(exception.getMessage().contains("access-token"));
        assertFalse(exception.getMessage().contains("client-secret"));
        assertNull(exception.getCause());
        assertFalse(unavailable.toString().contains("access-token"));
        assertFalse(unavailable.toString().contains("client-secret"));
    }

    @Test
    void distinguishesRequestTimeouts() {
        enqueue(new MockResponse(200, bytes(fixtureUnchecked("templates/list.json")), Map.of(), 200));
        PritsetClient client = PritsetClient.builder("token", "secret")
                .baseUri(baseUri)
                .timeout(Duration.ofMillis(20))
                .build();

        PritsetTransportException exception = assertThrows(PritsetTransportException.class, client.templates()::list);

        assertTrue(exception.isTimeout());
        assertFalse(exception.isInterrupted());
        assertNull(exception.getCause());
    }

    @Test
    void rejectsUnsafeConfigurationAndHeaderInjection() {
        List<String> invalidUris = List.of(
                "http://api.pritset.com",
                "https://user:pass@api.pritset.com",
                "https://api.pritset.com?token=unsafe",
                "ftp://api.pritset.com");
        for (String uri : invalidUris) {
            assertThrows(IllegalArgumentException.class, () -> PritsetClient.builder("token", "secret")
                    .baseUri(URI.create(uri))
                    .build());
        }
        assertThrows(IllegalArgumentException.class, () -> PritsetClient.builder("token\r\ninjected", "secret").build());
        HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        assertThrows(IllegalArgumentException.class, () -> PritsetClient.builder("token", "secret")
                .httpClient(redirecting)
                .build());
        assertThrows(IllegalArgumentException.class, () -> Upload.fromInputStream(
                new ByteArrayInputStream(new byte[0]),
                "unsafe\r\nname.docx"));
    }

    @Test
    void disablesPolymorphicDefaultTypingOnCustomMappers() throws Exception {
        ObjectMapper customMapper = new ObjectMapper();
        customMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);

        PritsetClient client = PritsetClient.builder("access-token", "client-secret")
                .baseUri(baseUri)
                .objectMapper(customMapper)
                .build();

        assertEquals("[]", client.objectMapper().writeValueAsString(new ArrayList<>()));
        assertTrue(customMapper.writeValueAsString(new ArrayList<>()).contains("java.util.ArrayList"));
    }

    @Test
    void rejectsInvalidJsonInputAndResponses() {
        enqueueJson(200, "not-json");
        PritsetClient client = client();

        assertThrows(PritsetTransportException.class, client.templates()::list);
        assertThrows(IllegalArgumentException.class, () -> client.documents().generate("template", "{invalid"));
        assertEquals(1, requests.size());
    }

    @Test
    void capsRetainedErrorBodies() {
        enqueue(new MockResponse(500, bytes("x".repeat(70_000)), Map.of()));

        PritsetApiException exception = assertThrows(PritsetApiException.class, client().templates()::list);

        assertEquals(64 * 1024, exception.responseBody().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void verifiesVendoredContractLockAndFixtures() throws Exception {
        Path openApi = Path.of("contract", "openapi.yaml");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(openApi)));
        JsonNode lock = new ObjectMapper().readTree(Path.of("contract", "contract.lock.json").toFile());

        assertEquals("1.0.0", lock.path("contractVersion").textValue());
        assertEquals(lock.path("openapiSha256").textValue(), hash);
        assertTrue(Files.readString(openApi).contains("  version: 1.0.0"));
        for (String fixture : List.of(
                "documents/webhook-job.json",
                "errors/field-errors.json",
                "errors/plain-text.txt",
                "errors/validation-problem.json",
                "templates/get.json",
                "templates/list.json")) {
            assertTrue(Files.isRegularFile(Path.of("contract", "fixtures", fixture)), fixture);
        }
    }

    private PritsetClient client() {
        return PritsetClient.builder("access-token", "client-secret")
                .baseUri(baseUri)
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private void enqueueJson(int status, String body) {
        enqueue(new MockResponse(status, bytes(body), Map.of("Content-Type", "application/json")));
    }

    private void enqueue(MockResponse response) {
        responses.add(response);
    }

    private RecordedRequest takeRequest() {
        RecordedRequest request = requests.poll();
        assertNotNull(request);
        return request;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders(),
                new String(body, StandardCharsets.UTF_8)));
        MockResponse response = responses.poll();
        if (response == null) {
            response = new MockResponse(500, bytes("No queued response"), Map.of());
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        if (response.status() == 204) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), response.body().length);
            exchange.getResponseBody().write(response.body());
        }
        exchange.close();
    }

    private static String fixture(String relativePath) throws IOException {
        return Files.readString(Path.of("contract", "fixtures", relativePath));
    }

    private static String fixtureUnchecked(String relativePath) {
        try {
            return fixture(relativePath);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record MockResponse(int status, byte[] body, Map<String, String> headers, long delayMillis) {
        private MockResponse(int status, byte[] body, Map<String, String> headers) {
            this(status, body, headers, 0);
        }
    }

    private record RecordedRequest(
            String method,
            String rawPath,
            String rawQuery,
            Headers headers,
            String body) {
        private String header(String name) {
            return headers.getFirst(name);
        }
    }
}
