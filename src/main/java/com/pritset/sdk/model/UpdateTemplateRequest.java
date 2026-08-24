package com.pritset.sdk.model;

import com.pritset.sdk.Upload;

/** Parameters for updating a Pritset template. */
public record UpdateTemplateRequest(String name, String tags, Upload template) {}
