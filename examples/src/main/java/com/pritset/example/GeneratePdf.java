package com.pritset.example;

import com.pritset.sdk.BinaryResponse;
import com.pritset.sdk.PritsetClient;
import java.nio.file.Path;
import java.util.Map;

/** Generates one PDF with credentials supplied through environment variables. */
public final class GeneratePdf {
    private GeneratePdf() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: GeneratePdf <template-id>");
            System.exit(2);
        }
        String token = requiredEnvironment("PRITSET_ACCESS_TOKEN");
        String secret = requiredEnvironment("PRITSET_SECRET");
        PritsetClient client = PritsetClient.builder(token, secret).build();

        Map<String, Object> invoice = Map.of(
                "invoice", Map.of("number", "INV-1042", "customer", "Ada Lovelace"));
        try (BinaryResponse pdf = client.documents().generate(args[0], invoice)) {
            pdf.save(Path.of("invoice.pdf"));
        }
        System.out.println("Saved invoice.pdf");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + ".");
        }
        return value;
    }
}
