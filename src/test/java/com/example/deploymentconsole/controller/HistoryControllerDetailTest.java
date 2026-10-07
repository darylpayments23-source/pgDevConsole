package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.model.DeploymentDetail;
import com.example.deploymentconsole.service.HistoryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryControllerDetailTest {

    private final HistoryService service = mock(HistoryService.class);
    private final HistoryController controller = new HistoryController(service);

    @Test void unknownIdIs404WithClearMessage() {
        when(service.detail("not-a-uuid")).thenReturn(Optional.empty());
        var r = controller.detail("not-a-uuid");
        assertEquals(404, r.getStatusCode().value());
        assertEquals(Map.of("error", "NOT_FOUND", "message", "Deployment not-a-uuid not found"), r.getBody());
    }

    @Test void knownIdReturnsTheDetail() {
        var d = DeploymentDetail.of(new DeploymentDetail.Header("id-1", "dev", "f", "SUCCESS", "all", "alice",
                null, null, null, 0, 0, 0), List.of());
        when(service.detail("id-1")).thenReturn(Optional.of(d));
        var r = controller.detail("id-1");
        assertEquals(200, r.getStatusCode().value());
        assertSame(d, r.getBody());
    }

    @Test void databaseUnavailableIs503() {
        var r = controller.unavailable(new IllegalStateException("down"));
        assertEquals(503, r.getStatusCode().value());
        assertEquals("SERVICE_UNAVAILABLE", r.getBody().get("error"));
    }
}
