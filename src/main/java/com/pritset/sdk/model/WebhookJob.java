package com.pritset.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A queued webhook document-generation job. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookJob(String id) {}
