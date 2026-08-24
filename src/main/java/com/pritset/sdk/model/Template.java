package com.pritset.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A Pritset DOCX template. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Template(String id, String name, String tags, String templateObject) {}
