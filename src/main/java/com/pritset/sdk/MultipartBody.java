package com.pritset.sdk;

import com.pritset.sdk.exception.PritsetTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MultipartBody implements AutoCloseable {
    private final String boundary = "pritset-" + UUID.randomUUID();
    private final List<BodyPublisher> publishers = new ArrayList<>();
    private final List<InputStream> streams = new ArrayList<>();
    private boolean completed;

    MultipartBody addField(String name, String value) {
        requireMutable();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + escapeQuoted(name) + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n";
        publishers.add(bytes(header));
        publishers.add(bytes(value));
        publishers.add(bytes("\r\n"));
        return this;
    }

    MultipartBody addUpload(String name, Upload upload) throws PritsetTransportException {
        requireMutable();
        InputStream stream;
        try {
            stream = upload.openStream();
        } catch (IOException exception) {
            throw new PritsetTransportException("The upload could not be opened.", false, false);
        }
        streams.add(stream);
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + escapeQuoted(name) + "\"; filename=\""
                + escapeQuoted(upload.fileName()) + "\"\r\n"
                + "Content-Type: " + upload.contentType() + "\r\n\r\n";
        publishers.add(bytes(header));
        publishers.add(BodyPublishers.ofInputStream(() -> stream));
        publishers.add(bytes("\r\n"));
        return this;
    }

    BodyPublisher publisher() {
        requireMutable();
        completed = true;
        publishers.add(bytes("--" + boundary + "--\r\n"));
        return BodyPublishers.concat(publishers.toArray(BodyPublisher[]::new));
    }

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    @Override
    public void close() {
        for (InputStream stream : streams) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Request completion already determines the operation result.
            }
        }
    }

    private void requireMutable() {
        if (completed) {
            throw new IllegalStateException("Multipart body is already complete");
        }
    }

    private static BodyPublisher bytes(String value) {
        return BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeQuoted(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Multipart names must not contain line breaks");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
