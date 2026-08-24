package com.pritset.sdk.model;

/** Filtering, paging, and sorting options for listing templates. */
public final class ListTemplatesOptions {
    private final String query;
    private final int page;
    private final int pageSize;
    private final String sortBy;
    private final SortDirection sortDirection;

    private ListTemplatesOptions(Builder builder) {
        if (builder.page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (builder.pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
        this.query = builder.query;
        this.page = builder.page;
        this.pageSize = builder.pageSize;
        this.sortBy = builder.sortBy;
        this.sortDirection = builder.sortDirection;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ListTemplatesOptions defaults() {
        return builder().build();
    }

    public String query() {
        return query;
    }

    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    public String sortBy() {
        return sortBy;
    }

    public SortDirection sortDirection() {
        return sortDirection;
    }

    /** Builds immutable list options. */
    public static final class Builder {
        private String query;
        private int page = 1;
        private int pageSize = 100;
        private String sortBy;
        private SortDirection sortDirection;

        private Builder() {}

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        public Builder sortDirection(SortDirection sortDirection) {
            this.sortDirection = sortDirection;
            return this;
        }

        public ListTemplatesOptions build() {
            return new ListTemplatesOptions(this);
        }
    }
}
