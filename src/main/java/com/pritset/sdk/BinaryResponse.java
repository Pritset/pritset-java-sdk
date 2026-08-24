package com.pritset.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** A streaming binary response returned by Pritset. */
public final class BinaryResponse implements AutoCloseable {
    private final InputStream body;
    private final String contentType;
    private final long contentLength;
    private final String trace;

    BinaryResponse(InputStream body, String contentType, long contentLength, String trace) {
        this.body = Objects.requireNonNull(body, "body");
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.trace = trace;
    }

    public InputStream body() {
        return body;
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    public OptionalLong contentLength() {
        return contentLength >= 0 ? OptionalLong.of(contentLength) : OptionalLong.empty();
    }

    public Optional<String> trace() {
        return Optional.ofNullable(trace);
    }

    /** Reads all remaining bytes into memory. Prefer streaming for large files. */
    public byte[] readAllBytes() throws IOException {
        return body.readAllBytes();
    }

    /** Saves all remaining bytes to a file, replacing an existing file. */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (OutputStream output = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            body.transferTo(output);
        }
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
