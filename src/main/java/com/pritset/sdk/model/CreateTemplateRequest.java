package com.pritset.sdk.model;

import com.pritset.sdk.Upload;

/** Parameters for creating a Pritset template. */
public record CreateTemplateRequest(String name, String tags, Upload template) {}
