package com.lily.cicd.config;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.HttpDatabaseProvisioner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * lily.database.provisioner-url 이 있을 때만 lily-db-provisioner 를 쓴다.
 * 없으면 이 설정은 빠지고 {@code NoopDatabaseProvisioner} 가 그대로 쓰인다.
 * {@code ModuleConfiguration} 의 기본 구현과 둘 다 등록되더라도 @Primary 인 이쪽이 주입된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "lily.database", name = "provisioner-url")
public class DatabaseModuleConfiguration {

    @Bean
    @Primary
    public DatabaseProvisioner httpDatabaseProvisioner(
            @Value("${lily.database.provisioner-url}") String provisionerUrl,
            @Value("${lily.database.api-token:}") String apiToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        // DB 생성은 1초 안쪽. RDS 가 느릴 때를 감안해 넉넉히 둔다
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return new HttpDatabaseProvisioner(
                RestClient.builder().requestFactory(requestFactory), provisionerUrl, apiToken);
    }
}
