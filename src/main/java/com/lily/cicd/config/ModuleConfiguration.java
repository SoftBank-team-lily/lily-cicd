package com.lily.cicd.config;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.module.TrafficRouter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 팀원 모듈이 같은 타입의 빈을 등록하면 여기 기본 구현은 빠진다.
 */
@Configuration
public class ModuleConfiguration {

    @Bean
    @ConditionalOnMissingBean(DatabaseProvisioner.class)
    public DatabaseProvisioner databaseProvisioner() {
        return new NoopDatabaseProvisioner();
    }

    @Bean
    @ConditionalOnMissingBean(TrafficRouter.class)
    public TrafficRouter trafficRouter(KubernetesClient kubernetesClient) {
        return new NginxIngressRouter(kubernetesClient);
    }

    @Bean
    @ConditionalOnMissingBean(DeployLog.class)
    public DeployLog deployLog() {
        return new Slf4jDeployLog();
    }

    @Bean
    @ConditionalOnMissingBean(DeployMonitor.class)
    public DeployMonitor deployMonitor() {
        return new NoopDeployMonitor();
    }
}
