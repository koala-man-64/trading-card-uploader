# Android Upload State Machine

```text
Captured -> Queued -> RequestingSas -> Uploading -> Uploaded -> Complete
```

Transient failure states:

```text
RequestingSas -> RetryWaiting
Uploading -> RetryWaiting
RetryWaiting -> RequestingSas
```

Terminal failures:

```text
Queued -> FailedTerminal
RequestingSas -> FailedTerminal
Uploading -> FailedTerminal
RetryWaiting -> FailedTerminal
```

## Retry Policy

Retry on network exceptions, 408, 429, 500, 502, 503, and 504.

Do not retry validation, authorization, oversized file, unsupported media type, or idempotency-conflict responses.

The first slice uses up to five WorkManager attempts. Future revisions should add a visible retry action and richer queue inspection UI.
