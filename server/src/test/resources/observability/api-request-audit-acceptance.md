# API Request Audit acceptance criteria

This feature must be validated on the 3.21.22 release branch against the following cases:

1. POST /api/tasks with a valid JSON TaskResult and HTTP 500: exactly one summary log includes requestId, workflowInstanceId, taskId, workerId, task status and HTTP status.
2. POST /api/tasks/update-v2 with a failure: fields are extracted from the cached consumed request body.
3. Slow successful request above conductor.audit.slow-request-ms: emits a summary with measured durationMs.
4. GET /api/tasks/poll/{tasktype} returning HTTP 204 under the threshold: no warning summary.
5. GET /api/tasks/{taskId} returning HTTP 500: taskId is derived from the URI.
6. Arbitrary inputData/outputData, authentication tokens, and query strings must never appear in audit logs.
7. Malformed JSON and payloads above the cache bound must not change HTTP behavior.
8. The filter must not interfere with asynchronous handling and should be profiled under concurrent polling.

Suggested commands:

    ./gradlew :conductor-server:compileJava
    ./gradlew :conductor-server:test --tests '*ApiRequestAuditFilterTest'

Do not merge before CI passes and the above integration/security review is complete.
