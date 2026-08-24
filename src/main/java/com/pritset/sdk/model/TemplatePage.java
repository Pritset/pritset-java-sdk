package com.pritset.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A page of Pritset templates. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplatePage(List<Template> data, int total) {
    public TemplatePage {
        data = data == null ? List.of() : List.copyOf(data);
    }
}
