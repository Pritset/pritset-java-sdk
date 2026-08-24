package com.pritset.sdk;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Describes a streamed DOCX upload. */
public final class Upload {
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StreamSource source;
    private final String fileName;
    private final String contentType;

    private Upload(StreamSource source, String fileName, String contentType) {
        this.source = source;
        this.fileName = validateFileName(fileName);
        this.contentType = validateContentType(contentType);
    }

    /** Creates a repeatable file-backed upload. */
    public static Upload fromPath(Path path) {
        return fromPath(path, DEFAULT_CONTENT_TYPE);
    }

    /** Creates a repeatable file-backed upload with an explicit media type. */
    public static Upload fromPath(Path path, String contentType) {
        Objects.requireNonNull(path, "path");
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Upload path must have a filename");
        }
        return new Upload(() -> Files.newInputStream(path), fileName.toString(), contentType);
    }

    /** Creates a single-use upload while leaving the caller's stream open. */
    public static Upload fromInputStream(InputStream stream, String fileName) {
        return fromInputStream(stream, fileName, DEFAULT_CONTENT_TYPE, true);
    }

    /** Creates a single-use stream upload. */
    public static Upload fromInputStream(
            InputStream stream,
            String fileName,
            String contentType,
            boolean leaveOpen) {
        Objects.requireNonNull(stream, "stream");
        AtomicBoolean opened = new AtomicBoolean();
        StreamSource source = () -> {
            if (!opened.compareAndSet(false, true)) {
                throw new IOException("Stream uploads can be sent only once");
            }
            return leaveOpen ? new NonClosingInputStream(stream) : stream;
        };
        return new Upload(source, fileName, contentType);
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    InputStream openStream() throws IOException {
        return source.open();
    }

    @Override
    public String toString() {
        return "Upload(fileName=" + fileName + ", contentType=" + contentType + ")";
    }

    private static String validateFileName(String value) {
        if (value == null || value.isBlank() || containsLineBreak(value)) {
            throw new IllegalArgumentException("Upload filename must not be blank or contain line breaks");
        }
        return value;
    }

    private static String validateContentType(String value) {
        if (value == null || value.isBlank() || containsLineBreak(value) || !value.contains("/")) {
            throw new IllegalArgumentException("Upload content type is invalid");
        }
        return value;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    @FunctionalInterface
    private interface StreamSource {
        InputStream open() throws IOException;
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream stream) {
            super(stream);
        }

        @Override
        public void close() {
            // Caller retains ownership.
        }
    }
}
