package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsUserWhenBlank() throws Exception {
        String json = objectMapper.writeValueAsString(
                new ExecuteRequest("echo prl-ready", "", Collections.emptyMap()));

        assertTrue(json.contains("\"command\":\"echo prl-ready\""));
        assertFalse(json.contains("\"user\""));
    }

    @Test
    void includesUserWhenPresent() throws Exception {
        String json = objectMapper.writeValueAsString(
                new ExecuteRequest("echo prl-ready", "parallels", Collections.emptyMap()));

        assertTrue(json.contains("\"user\":\"parallels\""));
    }
}