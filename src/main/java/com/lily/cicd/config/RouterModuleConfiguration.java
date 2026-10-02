package com.lily.cicd.config;

import com.lily.cicd.module.HttpTrafficRouter;
import com.lily.cicd.module.TrafficRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * lily.router.url 이 있을 때만 lily-router 를 쓴다.
 * 없으면 이 설정은 빠지고 {@code NginxIngressRouter} 가 그대로 쓰인다 (cicd 가 Ingress 를 직접 쓴다).
 * {@code ModuleConfiguration} 의 기본 구현과 둘 다 등록되더라도 @Primary 인 이쪽이 주입된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "lily.router", name = "url")
public class RouterModuleConfiguration {

    @Bean
    @Primary
    public TrafficRouter httpTrafficRouter(
            @Value("${lily.router.url}") String routerUrl,
            @Value("${lily.router.api-token:}") String apiToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        // Ingress 한 건을 쓰고 충돌을 검사하는 데 1초 안쪽이다
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return new HttpTrafficRouter(RestClient.builder().requestFactory(requestFactory), routerUrl, apiToken);
    }
}
