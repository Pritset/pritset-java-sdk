package com.pritset.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProductionLifecycleTest {
    @Test
    void acceptsOnlyExactProductionBaseUri() {
        ProductionLifecycle.BaseUri target = ProductionLifecycle.validateBaseUri("https://api.pritset.com");
        assertEquals(URI.create("https://api.pritset.com"), target.value());
        assertTrue(target.production());

        for (String unsafeUri : new String[] {
                "https://api.pritset.com/",
                "https://api.pritset.com:443",
                "https://api.pritset.com/api",
                "https://api.pritset.com?query=value",
                "https://api.pritset.com./"
        }) {
            assertThrows(IllegalArgumentException.class, () -> ProductionLifecycle.validateBaseUri(unsafeUri));
        }
    }

    @Test
    void acceptsHttpsAndExactLoopbackDevelopmentUris() {
        assertFalse(ProductionLifecycle.validateBaseUri("https://staging.example.com").production());
        assertFalse(ProductionLifecycle.validateBaseUri("http://localhost:8080").production());
        assertFalse(ProductionLifecycle.validateBaseUri("http://127.0.0.1:8080").production());
    }

    @Test
    void rejectsUnsafeNonProductionUris() {
        for (String unsafeUri : new String[] {
                "http://example.com",
                "http://localhost.:8080",
                "http://127.1:8080",
                "https://user:pass@example.com",
                "file:///tmp/template.docx"
        }) {
            assertThrows(IllegalArgumentException.class, () -> ProductionLifecycle.validateBaseUri(unsafeUri));
        }
    }

    @Test
    void acceptsSharedDocxFixture() {
        assertDoesNotThrow(() -> ProductionLifecycle.validateDocx(
                Path.of("tests", "fixtures", "staging-template.docx")));
    }
}
