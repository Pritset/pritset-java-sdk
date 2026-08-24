# Security policy

Please report suspected vulnerabilities privately to `security@pritset.com`. Do not include access tokens, secrets, customer documents, or production payloads in a public issue.

Only the latest released minor version receives security fixes during the pre-1.0 period. Rotate any credential that may have appeared in logs, exception dumps, shell history, or source control.

The SDK requires HTTPS except for exact loopback development endpoints. Its default `HttpClient` disables redirects, and injected clients are rejected unless their redirect policy is `NEVER`. Credential-bearing transport causes are discarded, error bodies are capped, uploads remain stream-based, and webhook URIs are validated but never fetched by the SDK.
