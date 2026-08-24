package com.pritset.sdk;

import com.pritset.sdk.exception.PritsetException;
import com.pritset.sdk.model.CreateTemplateRequest;
import com.pritset.sdk.model.ListTemplatesOptions;
import com.pritset.sdk.model.Template;
import com.pritset.sdk.model.TemplateDetails;
import com.pritset.sdk.model.TemplatePage;
import com.pritset.sdk.model.UpdateTemplateRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Template-management operations. */
public final class TemplatesResource {
    private final PritsetClient client;

    TemplatesResource(PritsetClient client) {
        this.client = client;
    }

    public TemplatePage list() throws PritsetException {
        return list(ListTemplatesOptions.defaults());
    }

    public TemplatePage list(ListTemplatesOptions options) throws PritsetException {
        Objects.requireNonNull(options, "options");
        Map<String, String> query = new LinkedHashMap<>();
        putIfPresent(query, "q", options.query());
        query.put("p", Integer.toString(options.page()));
        query.put("s", Integer.toString(options.pageSize()));
        putIfPresent(query, "sorts[0].sortBy", options.sortBy());
        if (options.sortDirection() != null) {
            query.put("sorts[0].sortDirection", Integer.toString(options.sortDirection().value()));
        }
        return client.sendJson("GET", "/api/template", query, null, TemplatePage.class);
    }

    public TemplateDetails get(String id) throws PritsetException {
        return client.sendJson("GET", templatePath("/api/template/", id), Map.of(), null, TemplateDetails.class);
    }

    public Template create(CreateTemplateRequest request) throws PritsetException {
        Objects.requireNonNull(request, "request");
        requireText(request.name(), "Template name");
        Objects.requireNonNull(request.template(), "request.template");
        try (MultipartBody body = new MultipartBody().addField("name", request.name())) {
            if (request.tags() != null) {
                body.addField("tags", request.tags());
            }
            body.addUpload("template", request.template());
            return client.sendJson("POST", "/api/template", Map.of(), body, Template.class);
        }
    }

    public Template update(String id, UpdateTemplateRequest request) throws PritsetException {
        Objects.requireNonNull(request, "request");
        requireText(request.name(), "Template name");
        try (MultipartBody body = new MultipartBody().addField("name", request.name())) {
            if (request.tags() != null) {
                body.addField("tags", request.tags());
            }
            if (request.template() != null) {
                body.addUpload("template", request.template());
            }
            return client.sendJson("PUT", templatePath("/api/template/", id), Map.of(), body, Template.class);
        }
    }

    public void delete(String id) throws PritsetException {
        client.sendEmpty("DELETE", templatePath("/api/template/", id));
    }

    public BinaryResponse download(String id) throws PritsetException {
        return client.sendBinary("GET", templatePath("/api/template/download/", id), null);
    }

    public boolean validate(Upload upload, Object data) throws PritsetException {
        Objects.requireNonNull(upload, "upload");
        try (MultipartBody body = new MultipartBody()
                .addField("data", DocumentData.serialize(data, client.objectMapper()))) {
            body.addUpload("file", upload);
            return client.sendBoolean("POST", "/api/template/process/validate", body);
        }
    }

    private static String templatePath(String prefix, String id) {
        requireText(id, "Template id");
        return prefix + UriCodec.encodePathSegment(id);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void putIfPresent(Map<String, String> values, String name, String value) {
        if (value != null) {
            values.put(name, value);
        }
    }
}
