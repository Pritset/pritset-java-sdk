package com.pritset.sdk;

import com.pritset.sdk.exception.PritsetApiException;
import com.pritset.sdk.exception.PritsetTransportException;
import com.pritset.sdk.model.CreateTemplateRequest;
import com.pritset.sdk.model.ListTemplatesOptions;
import com.pritset.sdk.model.Template;
import com.pritset.sdk.model.TemplateDetails;
import com.pritset.sdk.model.TemplatePage;
import com.pritset.sdk.model.UpdateTemplateRequest;
import com.pritset.sdk.model.WebhookJob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/** Guarded production test-user lifecycle. This class is intentionally test-only and is never packaged. */
public final class ProductionLifecycle {
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final int MAXIMUM_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAXIMUM_TEMPLATE_BYTES = 5_120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProductionLifecycle() {}

    public static void main(String[] args) {
        int exitCode = run(System.getenv(), Path.of(".").toAbsolutePath().normalize());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(Map<String, String> environment, Path workingDirectory) {
        Configuration configuration = null;
        PritsetClient client = null;
        String templateId = null;
        String originalName = null;
        String updatedName = null;
        boolean creationAttempted = false;
        boolean deleted = false;
        Exception failure = null;

        try {
            configuration = Configuration.load(environment, workingDirectory);
            String runId = Instant.now().toEpochMilli() + "-" + randomHex(4);
            originalName = configuration.runPrefix() + "-" + runId;
            updatedName = originalName + "-updated";
            Map<String, Object> data = Map.of(
                    "title", "Pritset SDK production test-user validation",
                    "description", "Lifecycle run " + runId,
                    "advantages", List.of(
                            Map.of("title", "Contract", "description", "All public template operations completed."),
                            Map.of("title", "Cleanup", "description", "The temporary template is deleted after validation.")));
            client = PritsetClient.builder(configuration.accessToken(), configuration.secret())
                    .baseUri(configuration.baseUri())
                    .timeout(Duration.ofSeconds(120))
                    .build();

            System.out.println("Validating template");
            boolean valid = client.templates().validate(
                    Upload.fromPath(configuration.templatePath(), DOCX_CONTENT_TYPE), data);
            require(valid, "Template validation returned false.");
            passed("validate template");

            creationAttempted = true;
            System.out.println("Creating template");
            Template created = client.templates().create(new CreateTemplateRequest(
                    originalName,
                    configuration.runPrefix() + ",java",
                    Upload.fromPath(configuration.templatePath(), DOCX_CONTENT_TYPE)));
            templateId = requiredResponseValue(created.id(), "Create response did not include a template ID.");
            require(originalName.equals(created.name()), "Create response returned an unexpected template name.");
            passed("create template");

            System.out.println("Filter templates");
            TemplatePage page = client.templates().list(ListTemplatesOptions.builder()
                    .query(originalName)
                    .page(1)
                    .pageSize(100)
                    .build());
            String createdTemplateId = templateId;
            require(page.data().stream().anyMatch(template -> createdTemplateId.equals(template.id())),
                    "Created template was not returned by list.");
            passed("list templates");

            System.out.println("Template details");
            TemplateDetails details = client.templates().get(templateId);
            require(details.template() != null && templateId.equals(details.template().id()),
                    "Template details returned an unexpected ID.");
            require(details.fileInfo() != null && details.fileInfo().size() > 0,
                    "Template details reported an empty file.");
            passed("get template details");

            System.out.println("Template update");
            Template updated = client.templates().update(templateId, new UpdateTemplateRequest(
                    updatedName,
                    configuration.runPrefix() + ",java,updated",
                    null));
            require(templateId.equals(updated.id()), "Update response returned an unexpected template ID.");
            require(updatedName.equals(updated.name()), "Update response returned an unexpected template name.");
            passed("update template");

            System.out.println("Template download");
            try (BinaryResponse download = client.templates().download(templateId)) {
                byte[] docx = readBounded(download.body(), MAXIMUM_RESPONSE_BYTES);
                require(docx.length > 4 && docx[0] == 'P' && docx[1] == 'K',
                        "Downloaded template was not a DOCX ZIP archive.");
            }
            passed("download template");

            System.out.println("Generate direct PDF");
            try (BinaryResponse document = client.documents().generate(templateId, data)) {
                byte[] pdf = readBounded(document.body(), MAXIMUM_RESPONSE_BYTES);
                require(pdf.length > 5
                                && "%PDF-".equals(new String(pdf, 0, 5, StandardCharsets.US_ASCII)),
                        "Generated document was not a PDF.");
            }
            passed("generate direct PDF");

            System.out.println("Generate webhook PDF");
            WebhookJob job = client.documents().generateWebhook(templateId, data, configuration.webhookUri());
            requiredResponseValue(job.id(), "Webhook response did not include a job ID.");
            passed("submit webhook PDF generation (delivery is not asserted)");

            if (configuration.webhookSettleSeconds() > 0) {
                System.out.println("Waiting " + configuration.webhookSettleSeconds()
                        + " seconds before template cleanup");
                sleepSeconds(configuration.webhookSettleSeconds());
            }

            System.out.println("Template deletion");
            client.templates().delete(templateId);
            deleted = true;
            passed("delete template");

            expectNotFound(client, templateId);
            passed("confirm deleted template returns 404");
            System.out.println("Java SDK production test-user lifecycle passed.");
        } catch (Exception exception) {
            failure = exception;
            System.err.println(safeMessage(exception));
            if (exception instanceof PritsetApiException apiException && apiException.statusCode() == 401) {
                System.err.println("Production authentication failed (401). Confirm PRITSET_ACCESS_TOKEN is the raw "
                        + "Pritset token without a Bearer prefix and PRITSET_SECRET is the matching secret for "
                        + "the same production test user.");
            }
        }

        if (configuration != null && client != null) {
            try {
                if (templateId != null && !deleted) {
                    deleteWithRetry(client, templateId);
                    System.out.println("Cleanup removed the temporary template.");
                } else if (creationAttempted && templateId == null && originalName != null && updatedName != null) {
                    cleanupByName(client, originalName, updatedName);
                }
            } catch (Exception cleanupException) {
                System.err.println("Cleanup failed: " + safeMessage(cleanupException));
                if (failure == null) {
                    failure = cleanupException;
                }
            }
        }
        return failure == null ? 0 : 1;
    }

    private static void cleanupByName(PritsetClient client, String originalName, String updatedName) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                TemplatePage page = client.templates().list(ListTemplatesOptions.builder()
                        .query(originalName)
                        .page(1)
                        .pageSize(100)
                        .build());
                List<Template> leaked = page.data().stream()
                        .filter(template -> originalName.equals(template.name()) || updatedName.equals(template.name()))
                        .toList();
                for (Template template : leaked) {
                    deleteWithRetry(client, template.id());
                    System.out.println("Fallback cleanup removed temporary template " + template.id() + ".");
                }
                if (!leaked.isEmpty() || attempt == 5) {
                    return;
                }
            } catch (Exception exception) {
                if (!isRetryable(exception) || attempt == 5) {
                    throw exception;
                }
            }
            sleepSeconds(1 << (attempt - 1));
        }
    }

    private static void deleteWithRetry(PritsetClient client, String templateId) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                client.templates().delete(templateId);
                return;
            } catch (PritsetApiException exception) {
                if (exception.statusCode() == 404) {
                    return;
                }
                if (!isRetryable(exception) || attempt == 5) {
                    throw exception;
                }
            } catch (Exception exception) {
                if (!isRetryable(exception) || attempt == 5) {
                    throw exception;
                }
            }
            sleepSeconds(1 << (attempt - 1));
        }
    }

    private static void expectNotFound(PritsetClient client, String templateId) throws Exception {
        try {
            client.templates().get(templateId);
        } catch (PritsetApiException exception) {
            if (exception.statusCode() == 404) {
                return;
            }
            throw exception;
        }
        throw new IllegalStateException("Deleted template remained accessible.");
    }

    private static boolean isRetryable(Exception exception) {
        return exception instanceof PritsetTransportException
                || exception instanceof PritsetApiException apiException
                        && (apiException.statusCode() == 429 || apiException.statusCode() >= 500);
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[81920];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Binary response exceeded the " + maximumBytes + "-byte safety limit.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void sleepSeconds(int seconds) throws IOException {
        try {
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Production lifecycle was interrupted.");
        }
    }

    private static String requiredResponseValue(String value, String message) {
        require(value != null && !value.isBlank(), message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void passed(String step) {
        System.out.println("PASS: " + step);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        StringBuilder result = new StringBuilder(bytes * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item));
        }
        return result.toString();
    }

    record BaseUri(URI value, boolean production) {}

    record Configuration(
            URI baseUri,
            String accessToken,
            String secret,
            URI webhookUri,
            Path templatePath,
            String runPrefix,
            int webhookSettleSeconds) {
        static Configuration load(Map<String, String> environment, Path workingDirectory) {
            BaseUri target = validateBaseUri(requiredEnvironment(environment, "PRITSET_BASE_URL"));
            if (target.production() && !"true".equals(environment.get("PRITSET_ALLOW_PRODUCTION"))) {
                throw new IllegalArgumentException("Refusing api.pritset.com without PRITSET_ALLOW_PRODUCTION=true.");
            }
            if (target.production() && !"true".equals(environment.get("PRITSET_PRODUCTION_TEST_USER_CONFIRMED"))) {
                throw new IllegalArgumentException("Refusing api.pritset.com until "
                        + "PRITSET_PRODUCTION_TEST_USER_CONFIRMED=true confirms dedicated test-user credentials.");
            }
            String accessToken = validateCredential(
                    requiredEnvironment(environment, "PRITSET_ACCESS_TOKEN"), "PRITSET_ACCESS_TOKEN");
            String secret = validateCredential(requiredEnvironment(environment, "PRITSET_SECRET"), "PRITSET_SECRET");
            URI webhookUri = validateWebhookUri(requiredEnvironment(environment, "PRITSET_WEBHOOK_URL"), target.production());
            String configuredPath = environment.get("PRITSET_TEMPLATE_PATH");
            if (configuredPath == null || configuredPath.isBlank()) {
                configuredPath = "tests/fixtures/staging-template.docx";
            }
            Path templatePath = workingDirectory.resolve(configuredPath).normalize();
            if (Path.of(configuredPath).isAbsolute()) {
                templatePath = Path.of(configuredPath).normalize();
            }
            validateDocx(templatePath);

            String runPrefix = environment.getOrDefault("PRITSET_TEST_RUN_PREFIX", "pritset-sdk-production-test").strip();
            if (!runPrefix.matches("[a-z0-9](?:[a-z0-9-]{0,46}[a-z0-9])?")) {
                throw new IllegalArgumentException("PRITSET_TEST_RUN_PREFIX must contain 1-48 lowercase letters, "
                        + "digits, or dashes and cannot end with a dash.");
            }
            String settleText = environment.getOrDefault("PRITSET_WEBHOOK_SETTLE_SECONDS", "10").strip();
            int settle;
            try {
                settle = Integer.parseInt(settleText);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("PRITSET_WEBHOOK_SETTLE_SECONDS must be an integer from 0 to 60.");
            }
            if (settle < 0 || settle > 60) {
                throw new IllegalArgumentException("PRITSET_WEBHOOK_SETTLE_SECONDS must be an integer from 0 to 60.");
            }
            return new Configuration(target.value(), accessToken, secret, webhookUri, templatePath, runPrefix, settle);
        }
    }

    static BaseUri validateBaseUri(String rawValue) {
        URI uri;
        try {
            uri = URI.create(rawValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PRITSET_BASE_URL must be an absolute HTTP(S) URI.");
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("PRITSET_BASE_URL must be an absolute HTTP(S) URI.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("PRITSET_BASE_URL cannot contain credentials, a query, or a fragment.");
        }
        String canonicalHost = uri.getHost().replace("[", "").replace("]", "").replaceFirst("\\.+$", "");
        boolean loopbackHttp = rawValue.matches("(?i)^http://(?:localhost|127[.]0[.]0[.]1|\\[::1\\])(?::[0-9]+)?(?:/.*)?$");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopbackHttp) {
            throw new IllegalArgumentException("PRITSET_BASE_URL must use HTTPS unless it targets an exact loopback host.");
        }
        boolean production = "api.pritset.com".equalsIgnoreCase(canonicalHost);
        if (production && !"https://api.pritset.com".equals(rawValue)) {
            throw new IllegalArgumentException("Production tests must target exactly https://api.pritset.com.");
        }
        return new BaseUri(uri, production);
    }

    static void validateDocx(Path path) {
        try {
            if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".docx")
                    || !Files.isRegularFile(path)
                    || !Files.isReadable(path)) {
                throw new IllegalArgumentException("PRITSET_TEMPLATE_PATH must identify a readable .docx file.");
            }
            long size = Files.size(path);
            if (size < 1 || size > MAXIMUM_TEMPLATE_BYTES) {
                throw new IllegalArgumentException("PRITSET_TEMPLATE_PATH must be between 1 byte and 5,120,000 bytes.");
            }
            try (ZipFile archive = new ZipFile(path.toFile())) {
                if (archive.getEntry("[Content_Types].xml") == null || archive.getEntry("word/document.xml") == null) {
                    throw new IllegalArgumentException("PRITSET_TEMPLATE_PATH is not a valid DOCX archive.");
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("PRITSET_TEMPLATE_PATH is not a readable DOCX file.");
        }
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank() || "replace-me".equals(value)) {
            throw new IllegalArgumentException("Set " + name + " before running the production lifecycle.");
        }
        return value;
    }

    private static String validateCredential(String value, String name) {
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
                || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must be the raw value without a Bearer prefix or whitespace.");
        }
        return value;
    }

    private static URI validateWebhookUri(String value, boolean production) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PRITSET_WEBHOOK_URL must be an absolute HTTP(S) URI without embedded credentials.");
        }
        boolean http = uri.isAbsolute() && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
        if (!http || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("PRITSET_WEBHOOK_URL must be an absolute HTTP(S) URI without embedded credentials.");
        }
        if (production && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("PRITSET_WEBHOOK_URL must use HTTPS for a production test.");
        }
        return uri;
    }
}
