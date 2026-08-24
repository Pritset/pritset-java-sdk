package com.pritset.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** File metadata associated with a stored template. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateFileInfo(
        String contentType,
        Instant lastModified,
        String objectName,
        long size) {}
