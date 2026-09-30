package com.lily.cicd.config;

import com.lily.cicd.deploy.BlueGreenDeploymentStrategy;
import com.lily.cicd.deploy.CanaryDeploymentStrategy;
import com.lily.cicd.deploy.DeploymentStrategy;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.module.TrafficRouter;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.SchemaMigrator;
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
    @ConditionalOnMissingBean(DeploymentStrategy.class)
    public DeploymentStrategy deploymentStrategy(KubernetesClient kubernetesClient, DeployProperties properties) {
        String name = properties.getStrategy() == null ? "" : properties.getStrategy().trim();
        if (name.isEmpty() || "blue-green".equals(name)) {
            return new BlueGreenDeploymentStrategy(kubernetesClient, properties);
        }
        if ("canary".equals(name)) {
            return new CanaryDeploymentStrategy(kubernetesClient, properties);
        }
        throw new IllegalArgumentException(
                "lily.deploy.strategy 는 blue-green 또는 canary 여야 한다: " + name);
    }

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

    /** 마이그레이션을 보낸 배포에서만 사용자 DB 에 붙는다 */
    @Bean
    public SchemaMigrator schemaMigrator() {
        return new SchemaMigrator();
    }

    @Bean
    public ReleaseStore releaseStore(KubernetesClient kubernetesClient) {
        return new ReleaseStore(kubernetesClient);
    }

    @Bean
    public DeployLock deployLock(KubernetesClient kubernetesClient) {
        return new DeployLock(kubernetesClient);
    }
}
