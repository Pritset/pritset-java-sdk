# Contributing

Use Java 17 or newer and Maven 3.9 or newer.

```bash
bash ./mvnw -B -ntp verify
bash ./mvnw -B -ntp install
bash ./mvnw -f examples/pom.xml -B -ntp verify
pwsh ./scripts/verify-contract.ps1
```

Changes to API behavior must remain compatible with `contract/openapi.yaml`. If the shared contract changes, update the vendored contract, fixtures, lock hash, tests, and README together.

The build treats compiler warnings as errors. Keep the public API Java 17-compatible, blocking, interruption-aware, and stream-first for large bodies. Do not introduce automatic retries without a contract and idempotency review.

Never commit credentials, `.env`, generated documents containing customer data, Maven artifacts, or build output.
