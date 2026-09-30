package com.lily.cicd.deploy;

import com.lily.cicd.release.DeployConflictException;
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

    @Mock
    private RollbackEngine rollbacker;

    @Test
    void 성공하면_200과_결과를_반환한다() {
        DeployController controller = new DeployController(engine, rollbacker);
        DeploymentResultDto dto = new DeploymentResultDto("SUCCESS", "canary", "http://lily.domain.com", List.of("ok"));
        when(engine.deploy(any(DeployCommand.class))).thenReturn(CompletableFuture.completedFuture(dto));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dto, response.getBody());
    }

    @Test
    void 잘못된_요청은_400이다() {
        DeployController controller = new DeployController(engine, rollbacker);
        when(engine.deploy(any(DeployCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("appName")));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(400, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("appName", body.message());
    }

    @Test
    void 배포_실패는_500과_로그를_반환한다() {
        DeployController controller = new DeployController(engine, rollbacker);
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
        DeployController controller = new DeployController(engine, rollbacker);
        when(engine.deploy(any(DeployCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));

        ResponseEntity<Object> response = controller.deploy(request()).join();

        assertEquals(500, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("deploy failed", body.message());
        assertEquals(List.of(), body.logs());
    }

    @Test
    void 롤백_성공은_200이고_본문이_없으면_기본값으로_부른다() {
        DeployController controller = new DeployController(engine, rollbacker);
        DeploymentResultDto dto = new DeploymentResultDto("ROLLED_BACK", "blue", "http://lily.domain.com", "2", List.of());
        when(rollbacker.rollback("lily", null, false)).thenReturn(CompletableFuture.completedFuture(dto));

        ResponseEntity<Object> response = controller.rollback("lily", null).join();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(dto, response.getBody());
    }

    @Test
    void 롤백_거절은_409와_로그를_반환한다() {
        DeployController controller = new DeployController(engine, rollbacker);
        when(rollbacker.rollback("lily", "apps", true))
                .thenThrow(new DeployConflictException("롤백할 수 없다", List.of("rollback: refused")));

        ResponseEntity<Object> response = controller.rollback("lily",
                new DeployController.RollbackRequest("apps", true)).join();

        assertEquals(409, response.getStatusCode().value());
        DeployController.DeployError body = assertInstanceOf(DeployController.DeployError.class, response.getBody());
        assertEquals("REJECTED", body.status());
        assertEquals(List.of("rollback: refused"), body.logs());
    }

    @Test
    void 롤백을_지원하지_않는_전략은_400이다() {
        DeployController controller = new DeployController(engine, rollbacker);
        when(rollbacker.rollback("lily", null, false)).thenReturn(
                CompletableFuture.failedFuture(new UnsupportedOperationException("canary")));

        assertEquals(400, controller.rollback("lily", null).join().getStatusCode().value());
    }

    @Test
    void 상태_조회는_200이고_잘못된_이름은_400이다() {
        DeployController controller = new DeployController(engine, rollbacker);
        RollbackEngine.ReleaseStatus status = new RollbackEngine.ReleaseStatus(
                "lily", "default", "green", List.of(), true, null);
        when(rollbacker.status("lily", null)).thenReturn(status);
        when(rollbacker.status("Bad_Name", null)).thenThrow(new IllegalArgumentException("appName"));

        assertEquals(status, controller.status("lily", null).getBody());
        assertEquals(400, controller.status("Bad_Name", null).getStatusCode().value());
    }

    private static DeployRequest request() {
        return new DeployRequest(
                "lily", "image:1", 8080, null, null, null, null, "1.0.0", null, Map.of());
    }
}
