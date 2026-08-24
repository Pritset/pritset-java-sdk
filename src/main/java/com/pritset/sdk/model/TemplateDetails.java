package com.pritset.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A template and its stored-file metadata. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateDetails(Template template, TemplateFileInfo fileInfo) {}
