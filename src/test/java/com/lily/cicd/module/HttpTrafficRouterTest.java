package com.lily.cicd.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** lily-router 응답을 흉내 내서 요청 경로·본문·토큰과 404·5xx 처리를 검증한다 */
class HttpTrafficRouterTest {

    private static final String BASE = "http://lily-router";
    private static final String ROUTE = BASE + "/api/v1/routes/default/blog";
    private static final String CANARY = ROUTE + "/canary";
    private static final String NOT_FOUND = """
            {"timestamp":"2026-10-02T05:00:00Z","code":"ROUTE_NOT_FOUND","message":"default/blog 라우트가 없다"}""";

    private MockRestServiceServer server;
    private HttpTrafficRouter router;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        router = new HttpTrafficRouter(builder, BASE, "token", 0);
    }

    @Test
    void route_는_서비스와_호스트와_배포_정보를_보낸다() {
        server.expect(requestTo(ROUTE))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().json("""
                        {"serviceName":"blog-svc","servicePort":80,"host":"blog.lilycloud.kr",
                         "deployment":{"slot":"green","version":"2","image":"image:2","status":"ACTIVE"}}"""))
                .andRespond(json("{}"));

        router.route(context());

        server.verify();
    }

    @Test
    void 연결_실패와_5xx_는_다시_보내고_끝내_실패하면_던진다() {
        server.expect(ExpectedCount.times(HttpTrafficRouter.ATTEMPTS), requestTo(ROUTE))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThrows(HttpServerErrorException.class, () -> router.route(context()));
        server.verify();
    }

    @Test
    void 한_번_실패한_뒤_성공하면_그대로_진행한다() {
        server.expect(requestTo(ROUTE)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(ROUTE)).andRespond(json("{}"));

        router.route(context());

        server.verify();
    }

    @Test
    void 클라이언트_오류_4xx_는_다시_보내지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(ROUTE))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"HOST_CONFLICT\",\"message\":\"dup\"}"));

        assertThrows(HttpClientErrorException.Conflict.class, () -> router.route(context()));
        server.verify();
    }

    @Test
    void canary_는_라우트가_있으면_바로_연다() {
        server.expect(requestTo(CANARY))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"serviceName\":\"blog-canary-svc\",\"servicePort\":80,\"weight\":0}"))
                .andRespond(json("{}"));

        router.openCanary(context(), "blog-canary-svc", 0);

        server.verify();
    }

    @Test
    void canary_는_라우트가_없으면_먼저_등록하고_다시_연다() {
        server.expect(requestTo(CANARY)).andRespond(notFound());
        server.expect(requestTo(ROUTE)).andExpect(method(HttpMethod.PUT)).andRespond(json("{}"));
        server.expect(requestTo(CANARY)).andExpect(method(HttpMethod.PUT)).andRespond(json("{}"));

        router.openCanary(context(), "blog-canary-svc", 20);

        server.verify();
    }

    @Test
    void canary_닫기는_라우트가_없어도_성공이다() {
        server.expect(requestTo(CANARY)).andExpect(method(HttpMethod.DELETE)).andRespond(notFound());

        router.closeCanary("default", "blog");

        server.verify();
    }

    @Test
    void routes_는_응답_hosts_에_그_호스트가_있는지_본다() {
        server.expect(requestTo(ROUTE)).andExpect(method(HttpMethod.GET))
                .andRespond(json("""
                        {"namespace":"default","app":"blog","url":"https://blog.lilycloud.kr",
                         "hosts":["blog.lilycloud.kr","blog.43.200.152.53.nip.io"],"backends":[]}"""));
        server.expect(requestTo(ROUTE)).andRespond(notFound());

        assertTrue(router.routes("default", "blog", "blog.43.200.152.53.nip.io"));
        assertFalse(router.routes("default", "blog", "blog.lilycloud.kr"));
        server.verify();
    }

    @Test
    void openCanaries_는_canary_가_열린_라우트만_묻는다() {
        server.expect(requestTo(BASE + "/api/v1/routes?canary=true"))
                .andRespond(json("""
                        [{"namespace":"default","app":"blog","hosts":["blog.lilycloud.kr"],
                          "canary":{"serviceName":"blog-canary-svc","servicePort":80,"weight":20}}]"""));

        assertEquals(List.of(new TrafficRouter.AppRef("default", "blog")), router.openCanaries());
        server.verify();
    }

    @Test
    void remove_는_라우트를_지우고_없으면_빈_목록이다() {
        server.expect(requestTo(ROUTE)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(ROUTE)).andExpect(method(HttpMethod.DELETE)).andRespond(notFound());

        assertEquals(List.of("route/blog"), router.remove("default", "blog"));
        assertEquals(List.of(), router.remove("default", "blog"));
        server.verify();
    }

    @Test
    void 토큰이_없으면_Authorization_을_보내지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer noToken = MockRestServiceServer.bindTo(builder).build();
        HttpTrafficRouter open = new HttpTrafficRouter(builder, BASE, "", 0);
        noToken.expect(requestTo(CANARY))
                .andExpect(request -> assertFalse(request.getHeaders().containsKey("Authorization")))
                .andRespond(json("{}"));

        open.closeCanary("default", "blog");

        noToken.verify();
    }

    private static DeployContext context() {
        return new DeployContext("blog", "default", "image:2", 8080, 80, "blog.lilycloud.kr", "2",
                "green", "blog-svc", "/actuator/prometheus");
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static ResponseCreator notFound() {
        return withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(NOT_FOUND);
    }
}
