package com.lily.cicd.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeployControllerTest {

    @Mock
    private DeploymentEngine engine;

    @Test
    void 성공하면_200과_결과를_반환한다() {
        DeployController controller = new DeployController(engine);
        DeploymentResultDto dto = new DeploymentResultDto("SUCCESS", "canary", "http://lily.domain.com", List.of("ok"));
        when(engine.deploy(any(DeployCommand.class))).thenReturn(CompletableFuture.completedFuture(dto));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dto, response.getBody());
    }

    @Test
    void 잘못된_요청은_400이다() {
        DeployController controller = new DeployController(engine);
        when(engine.deploy(any(DeployCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("appName")));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(400, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("appName", body.message());
    }

    @Test
    void 배포_실패는_500과_로그를_반환한다() {
        DeployController controller = new DeployController(engine);
        when(engine.deploy(any(DeployCommand.class))).thenReturn(CompletableFuture.failedFuture(
                new DeploymentFailedException("boom", List.of("step1"), null)));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(500, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("boom", body.message());
        assertEquals(List.of("step1"), body.logs());
    }

    @Test
    void 원인이_없는_실패는_500이다() {
        DeployController controller = new DeployController(engine);
        when(engine.deploy(any(DeployCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(500, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("deploy failed", body.message());
        assertEquals(List.of(), body.logs());
    }

    private static DeployRequest request() {
        return new DeployRequest(
                "lily", "image:1", 8080, null, null, null, null, "1.0.0", null, Map.of());
    }
}
