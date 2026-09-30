package com.lily.cicd.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** lily-db-provisioner 응답을 흉내 내서 조회 → 생성 → env 순서를 검증한다 */
class HttpDatabaseProvisionerTest {

    private static final String BASE = "http://provisioner";
    private static final String LIST = BASE + "/api/databases?projectId=blog";
    private static final String CREATE = BASE + "/api/databases";
    private static final String ENV = BASE + "/api/databases/db-1/env";
    private static final String ENV_BODY = """
            {"databaseId":"db-1","env":{"SPRING_DATASOURCE_URL":"jdbc:postgresql://h:5432/p_1","DB_PASSWORD":"pw"}}""";

    private MockRestServiceServer server;
    private HttpDatabaseProvisioner provisioner;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provisioner = new HttpDatabaseProvisioner(builder, BASE, "token");
    }

    @Test
    void database_가_없으면_프로비저너를_부르지_않는다() {
        DeployContext frontend = new DeployContext("web", "default", "img", 3000, 80, "web.domain.com",
                "1.0.0", null, "web-svc", "/metrics");

        assertEquals(Map.of(), provisioner.prepare(frontend));
        server.verify();
    }

    @Test
    void 이미_있으면_그대로_env_를_돌려준다() {
        server.expect(requestTo(LIST))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(json("[{\"id\":\"db-1\",\"projectId\":\"blog\",\"status\":\"AVAILABLE\"}]"));
        server.expect(requestTo(ENV)).andRespond(json(ENV_BODY));

        Map<String, String> env = provisioner.prepare(context());

        assertEquals("jdbc:postgresql://h:5432/p_1", env.get("SPRING_DATASOURCE_URL"));
        assertEquals("pw", env.get("DB_PASSWORD"));
        server.verify();
    }

    @Test
    void 없으면_만들고_env_를_돌려준다() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        server.expect(requestTo(CREATE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"projectId\":\"blog\",\"engine\":\"postgres\"}"))
                .andRespond(json("{\"id\":\"db-1\",\"projectId\":\"blog\",\"status\":\"AVAILABLE\"}"));
        server.expect(requestTo(ENV)).andRespond(json(ENV_BODY));

        assertEquals("pw", provisioner.prepare(context()).get("DB_PASSWORD"));
        server.verify();
    }

    @Test
    void FAILED_면_다시_만든다() {
        server.expect(requestTo(LIST))
                .andRespond(json("[{\"id\":\"old\",\"projectId\":\"blog\",\"status\":\"FAILED\"}]"));
        server.expect(requestTo(CREATE)).andExpect(method(HttpMethod.POST))
                .andRespond(json("{\"id\":\"db-1\",\"projectId\":\"blog\",\"status\":\"AVAILABLE\"}"));
        server.expect(requestTo(ENV)).andRespond(json(ENV_BODY));

        assertEquals("pw", provisioner.prepare(context()).get("DB_PASSWORD"));
        server.verify();
    }

    @Test
    void 동시에_먼저_만들어졌으면_다시_조회해서_쓴다() {
        server.expect(requestTo(LIST)).andRespond(json("[]"));
        server.expect(requestTo(CREATE)).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(LIST))
                .andRespond(json("[{\"id\":\"db-1\",\"projectId\":\"blog\",\"status\":\"AVAILABLE\"}]"));
        server.expect(requestTo(ENV)).andRespond(json(ENV_BODY));

        assertEquals("pw", provisioner.prepare(context()).get("DB_PASSWORD"));
        server.verify();
    }

    @Test
    void 사용할_수_없는_상태면_배포를_막는다() {
        server.expect(requestTo(LIST))
                .andRespond(json("[{\"id\":\"db-1\",\"projectId\":\"blog\",\"status\":\"CREATING\"}]"));

        assertThrows(IllegalStateException.class, () -> provisioner.prepare(context()));
        server.verify();
    }

    @Test
    void 프로비저너_오류는_그대로_실패한다() {
        server.expect(requestTo(LIST)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(RuntimeException.class, () -> provisioner.prepare(context()));
        server.verify();
    }

    private static DeployContext context() {
        return new DeployContext("blog", "default", "img", 8080, 80, "blog.domain.com",
                "1.0.0", null, "blog-svc", "/actuator/prometheus", "postgres");
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
