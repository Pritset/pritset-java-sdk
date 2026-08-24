package com.pritset.sdk;

import com.pritset.sdk.exception.PritsetException;
import com.pritset.sdk.model.WebhookJob;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** PDF document-generation operations. */
public final class DocumentsResource {
    private final PritsetClient client;

    DocumentsResource(PritsetClient client) {
        this.client = client;
    }

    public BinaryResponse generate(String templateId, Object data) throws PritsetException {
        try (MultipartBody body = new MultipartBody()
                .addField("data", DocumentData.serialize(data, client.objectMapper()))) {
            return client.sendBinary("POST", templatePath("/api/template/process/direct/", templateId), body);
        }
    }

    public WebhookJob generateWebhook(String templateId, Object data, URI webhookUri) throws PritsetException {
        validateWebhookUri(webhookUri);
        try (MultipartBody body = new MultipartBody()
                .addField("data", DocumentData.serialize(data, client.objectMapper()))
                .addField("url", webhookUri.toString())) {
            return client.sendJson(
                    "POST",
                    templatePath("/api/template/process/webhook/", templateId),
                    Map.of(),
                    body,
                    WebhookJob.class);
        }
    }

    private static void validateWebhookUri(URI value) {
        Objects.requireNonNull(value, "webhookUri");
        boolean http = value.isAbsolute()
                && (value.getScheme().equalsIgnoreCase("http") || value.getScheme().equalsIgnoreCase("https"));
        if (!http || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "webhookUri must be an absolute HTTP(S) URI without credentials or a fragment");
        }
    }

    private static String templatePath(String prefix, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Template id is required");
        }
        return prefix + UriCodec.encodePathSegment(id);
    }
}
