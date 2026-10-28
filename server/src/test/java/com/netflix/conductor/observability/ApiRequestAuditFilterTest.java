package com.netflix.conductor.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class ApiRequestAuditFilterTest {

    private final ApiRequestAuditFilter filter =
            new ApiRequestAuditFilter(new ObjectMapper(), 1000);

    @Test
    void preservesTaskUpdateBodyAndRequestIdOnServerError() throws Exception {
        byte[] body = ("{\"workflowInstanceId\":\"wf-123\","
                + "\"taskId\":\"task-456\",\"status\":\"COMPLETED\","
                + "\"outputData\":{\"secret\":\"do-not-log\"}}")
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.setContentType("application/json");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            byte[] read = req.getInputStream().readAllBytes();
            assertEquals(new String(body, StandardCharsets.UTF_8),
                    new String(read, StandardCharsets.UTF_8));
            ((MockHttpServletResponse) res).setStatus(500);
        });

        assertEquals(500, response.getStatus());
        assertNotNull(response.getHeader("X-Request-ID"));
    }

    @Test
    void preservesNormalNoTaskPollingResponse() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/tasks/poll/example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(204));
        assertEquals(204, response.getStatus());
        assertNotNull(response.getHeader("X-Request-ID"));
    }
}
