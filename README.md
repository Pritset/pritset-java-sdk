# Pritset Java SDK

Official Java client for managing Pritset DOCX templates and generating PDFs.

Version `0.1.5` targets Pritset SDK contract `1.0.0`.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- A Pritset access token and secret

CI verifies Java 17, 21, and 25. The SDK uses Java's built-in `HttpClient` and Jackson 2.x.

## Installation

Install version `0.1.5` from Maven Central:

```xml
<dependency>
  <groupId>com.pritset</groupId>
  <artifactId>pritset-java</artifactId>
  <version>0.1.5</version>
</dependency>
```

## Create a client

```java
import com.pritset.sdk.PritsetClient;

PritsetClient pritset = PritsetClient.builder(
        System.getenv("PRITSET_ACCESS_TOKEN"),
        System.getenv("PRITSET_SECRET"))
    .build();
```

Pritset expects the access token directly in the `Authorization` header—do not add a `Bearer` prefix. The secret is sent in `X-Secret`. `PritsetClient.toString()` redacts both credentials; never commit or log them.

## Generate and save a PDF

Binary responses stream through `InputStream`. Always close `BinaryResponse`.

```java
import com.pritset.sdk.BinaryResponse;
import java.nio.file.Path;
import java.util.Map;

Map<String, Object> data = Map.of(
    "invoice", Map.of("number", "INV-1042", "customer", "Ada Lovelace"));

try (BinaryResponse pdf = pritset.documents().generate("template-id", data)) {
    pdf.save(Path.of("invoice.pdf"));
}
```

For direct stream processing:

```java
try (BinaryResponse pdf = pritset.documents().generate("template-id", data)) {
    pdf.body().transferTo(destination);
}
```

Response metadata is exposed through `contentType()`, `contentLength()`, and `trace()`. `readAllBytes()` is available for known-small bodies.

## Manage templates

```java
import com.pritset.sdk.model.ListTemplatesOptions;
import com.pritset.sdk.model.SortDirection;
import com.pritset.sdk.model.TemplateDetails;
import com.pritset.sdk.model.TemplatePage;

TemplatePage page = pritset.templates().list(ListTemplatesOptions.builder()
    .query("invoice")
    .page(1)
    .pageSize(25)
    .sortBy("name")
    .sortDirection(SortDirection.ASCENDING)
    .build());

TemplateDetails details = pritset.templates().get("template-id");
```

### Create and update

`Upload.fromPath` is repeatable and opens the file only for the request. `Upload.fromInputStream` is single-use and leaves the caller's stream open by default.

```java
import com.pritset.sdk.Upload;
import com.pritset.sdk.model.CreateTemplateRequest;
import com.pritset.sdk.model.Template;
import com.pritset.sdk.model.UpdateTemplateRequest;
import java.nio.file.Path;

Upload upload = Upload.fromPath(
    Path.of("invoice.docx"),
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

Template created = pritset.templates().create(new CreateTemplateRequest(
    "Monthly invoice",
    "invoice,monthly",
    upload));

Template updated = pritset.templates().update(
    created.id(),
    new UpdateTemplateRequest("Monthly invoice v2", "invoice,monthly", null));
```

### Download and delete

```java
try (BinaryResponse template = pritset.templates().download("template-id")) {
    template.save(Path.of("downloaded-template.docx"));
}

pritset.templates().delete("template-id");
```

## Validate a template

```java
boolean valid = pritset.templates().validate(upload, Map.of(
    "invoice", Map.of("number", "INV-1042")));
```

Template validation uses the same token-and-secret authentication as the other public template operations and is supported by contract `1.0.0`.

## Webhook generation

```java
import com.pritset.sdk.model.WebhookJob;
import java.net.URI;

WebhookJob job = pritset.documents().generateWebhook(
    "template-id",
    data,
    URI.create("https://example.com/webhooks/pritset"));
```

Webhook URIs must be absolute HTTP(S) URIs without embedded credentials or fragments. The SDK sends the URI to Pritset; it does not fetch or call the webhook itself.

## Raw JSON

Pass a JSON string when data is already encoded. Invalid or trailing JSON is rejected before a request is sent.

```java
try (BinaryResponse pdf = pritset.documents().generate(
        "template-id",
        "{\"name\":\"Ada\"}")) {
    // Consume pdf.body().
}
```

## Errors

```java
import com.pritset.sdk.exception.PritsetApiException;
import com.pritset.sdk.exception.PritsetTransportException;

try {
    pritset.templates().get("template-id");
} catch (PritsetApiException exception) {
    System.out.println(exception.statusCode());
    System.out.println(exception.fieldErrors());
    exception.traceId().ifPresent(System.out::println);
    exception.retryAfter().ifPresent(System.out::println);
} catch (PritsetTransportException exception) {
    System.out.printf("timeout=%s interrupted=%s%n",
        exception.isTimeout(), exception.isInterrupted());
}
```

API error bodies are capped at 64 KiB. Transport exceptions intentionally do not retain their original exception because it may reference a request containing authentication headers. The SDK does not retry automatically in `0.1.x`.

## Timeouts and interruption

```java
import java.time.Duration;

PritsetClient pritset = PritsetClient.builder(token, secret)
    .timeout(Duration.ofSeconds(90))
    .build();
```

The timeout applies to every request. Blocking calls can also be canceled by interrupting their thread; the SDK restores the interrupt flag and throws `PritsetTransportException` with `isInterrupted()` set.

## Custom HTTP clients and local development

Injected clients remain caller-owned and must use `HttpClient.Redirect.NEVER`:

```java
import java.net.http.HttpClient;

HttpClient httpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

PritsetClient pritset = PritsetClient.builder(token, secret)
    .httpClient(httpClient)
    .build();
```

The SDK-created client also disables redirects so credentials cannot be forwarded to another origin. API base URIs require HTTPS except for exact `localhost`, `127.0.0.1`, or `::1` development endpoints:

```java
PritsetClient local = PritsetClient.builder("test-token", "test-secret")
    .baseUri(URI.create("http://127.0.0.1:8080"))
    .build();
```

## Runnable example

Install the SDK locally, then compile or run the example:

```bash
bash ./mvnw -B -ntp install
bash ./mvnw -f examples/pom.xml -B -ntp verify
bash ./mvnw -f examples/pom.xml exec:java -Dexec.args=template-id
```

The example reads `PRITSET_ACCESS_TOKEN` and `PRITSET_SECRET` and saves `invoice.pdf`.

## Contract and API documentation

- SDK contract: [`pritset/pritset-sdk-contract`](https://github.com/pritset/pritset-sdk-contract), version `1.0.0`
- API documentation: [pritset.com/docs/api](https://pritset.com/docs/api)

## Production test-user lifecycle

The opt-in lifecycle validates a DOCX, creates, lists, reads, updates, downloads, and deletes a temporary template, generates a PDF, submits a webhook generation job, and confirms the deleted template returns `404`. It verifies webhook submission only; webhook delivery must be monitored separately.

The test uses real production credit and must run only with the dedicated production test user. Copy `.env.example` to `.env`, enter the production test-user credentials and a controlled HTTPS webhook URL, then change both production guard values to `true` only after confirming the account is the dedicated test user. Run:

```powershell
& ./scripts/run-production-test.ps1
```

The launcher validates the configuration, builds without credentials in the process environment, asks you to type `RUN-PRODUCTION-TEST`, and runs the compiled lifecycle with secrets loaded only for that final process. It always attempts to remove a created template, including when creation returns an ambiguous failure.

GitHub Actions provides the same test through the **Production test-user lifecycle** workflow. Configure the `production-test` environment with approval protection; add `PRITSET_ACCESS_TOKEN`, `PRITSET_SECRET`, and `PRITSET_WEBHOOK_URL` secrets plus a `PRITSET_PRODUCTION_TEST_USER_CONFIRMED` environment variable set to the exact value `true`. The optional `PRITSET_WEBHOOK_SETTLE_SECONDS` environment variable defaults to 10 seconds.

## Development

```bash
bash ./mvnw -B -ntp verify
pwsh ./scripts/verify-contract.ps1
```

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## License

MIT © Pritset. See [LICENSE](LICENSE).
