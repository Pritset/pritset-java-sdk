package com.pritset.sdk;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class UriCodec {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UriCodec() {}

    static URI build(URI baseUri, String path, Map<String, String> query) {
        StringBuilder value = new StringBuilder(baseUri.toString().replaceAll("/+$", ""));
        value.append('/').append(path.replaceAll("^/+", ""));
        if (!query.isEmpty()) {
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> item : query.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                first = false;
                value.append(encode(item.getKey())).append('=').append(encode(item.getValue()));
            }
        }
        return URI.create(value.toString());
    }

    static String encodePathSegment(String value) {
        return encode(value);
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '.'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }
}
