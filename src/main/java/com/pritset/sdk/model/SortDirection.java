package com.pritset.sdk.model;

/** Sort direction accepted by the Pritset API. */
public enum SortDirection {
    ASCENDING(0),
    DESCENDING(1);

    private final int value;

    SortDirection(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
