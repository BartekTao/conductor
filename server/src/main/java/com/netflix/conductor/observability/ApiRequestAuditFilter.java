package com.netflix.conductor.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Audit allowlisted identifiers from slow or failed Conductor HTTP requests. */
@Component
public class ApiRequestAuditFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestAuditFilter.class);
    private static final int MAX_CACHED_BODY = 65536;
    private final ObjectMapper mapper;
    private final long slowThresholdMs;

    public ApiRequestAuditFilter(
            ObjectMapper mapper,
            @Value("${conductor.audit.slow-request-ms:1000}") long slowThresholdMs) {
        this.mapper = mapper;
        this.slowThresholdMs = slowThresholdMs;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        long started = System.nanoTime();
        boolean unhandled = false;
        ContentCachingRequestWrapper wrapped =
                new ContentCachingRequestWrapper(request, MAX_CACHED_BODY);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            try {
                chain.doFilter(wrapped, response);
            } catch (IOException | ServletException | RuntimeException | Error e) {
                unhandled = true;
                throw e;
            } finally {
                long durationMs =
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                int status = unhandled && response.getStatus() < 500
                        ? 500 : response.getStatus();
                if (status >= 500 || durationMs >= slowThresholdMs) {
                    RequestFields fields = extract(wrapped);
                    LOGGER.warn(
                            "conductor_api_request requestId={} method={} path={} status={} durationMs={} workflowId={} taskId={} taskType={} workerId={} taskStatus={}",
                            requestId, safe(request.getMethod()), safe(request.getRequestURI()),
                            status, durationMs, fields.workflowId, fields.taskId,
                            fields.taskType, fields.workerId, fields.taskStatus);
                }
            }
        }
    }

    private RequestFields extract(ContentCachingRequestWrapper request) {
        RequestFields fields = new RequestFields();
        String path = request.getRequestURI();
        String[] segments = path.split("/");
        if (segments.length >= 4 && "api".equals(segments[1])) {
            if ("workflow".equals(segments[2]) && segments.length == 4) {
                fields.workflowId = safe(segments[3]);
            } else if ("tasks".equals(segments[2])) {
                if (segments.length == 4 && !"search".equals(segments[3])
                        && !"update-v2".equals(segments[3])
                        && !"queue".equals(segments[3])
                        && !"poll".equals(segments[3])) {
                    fields.taskId = safe(segments[3]);
                } else if (segments.length >= 5 && "poll".equals(segments[3])) {
                    fields.taskType = safe(segments.length >= 6
                            && "batch".equals(segments[4]) ? segments[5] : segments[4]);
                    fields.workerId = safe(request.getParameter("workerid"));
                } else if (segments.length >= 6 && !"queue".equals(segments[3])) {
                    fields.workflowId = safe(segments[3]);
                    fields.taskType = safe(segments[4]);
                    fields.taskStatus = safe(segments[5]);
                }
            }
        }
        boolean taskUpdate = "POST".equals(request.getMethod())
                && ("/api/tasks".equals(path) || "/api/tasks/update-v2".equals(path));
        String contentType = request.getContentType();
        if (taskUpdate && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            byte[] cached = request.getContentAsByteArray();
            if (cached.length > 0 && cached.length < MAX_CACHED_BODY) {
                try {
                    JsonNode body = mapper.readTree(new String(cached, StandardCharsets.UTF_8));
                    fields.workflowId = safe(text(body, "workflowInstanceId"));
                    fields.taskId = safe(text(body, "taskId"));
                    fields.workerId = safe(text(body, "workerId"));
                    fields.taskStatus = safe(text(body, "status"));
                } catch (IOException ignored) {
                    // Never change endpoint behavior for invalid request JSON.
                }
            }
        }
        return fields;
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body == null ? null : body.get(name);
        return value != null && value.isTextual() ? value.asText() : "-";
    }

    private static String safe(String input) {
        if (input == null || input.isEmpty()) {
            return "-";
        }
        String cleaned = input.replaceAll("[^a-zA-Z0-9_.:@-]", "_");
        return cleaned.substring(0, Math.min(128, cleaned.length()));
    }

    private static class RequestFields {
        String workflowId = "-";
        String taskId = "-";
        String taskType = "-";
        String workerId = "-";
        String taskStatus = "-";
    }
}
