package com.lily.cicd.config;

import com.lily.cicd.deploy.BlueGreenDeploymentStrategy;
import com.lily.cicd.deploy.CanaryDeploymentStrategy;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleConfigurationTest {

    private final ModuleConfiguration configuration = new ModuleConfiguration();

    @Test
    void 전략_이름이_없으면_블루그린이다() {
        assertInstanceOf(BlueGreenDeploymentStrategy.class,
                configuration.deploymentStrategy(null, new DeployProperties()));
    }

    @Test
    void 전략_이름이_canary면_카나리_구현을_만든다() {
        DeployProperties properties = new DeployProperties();
        properties.setStrategy("canary");
        assertInstanceOf(CanaryDeploymentStrategy.class,
                configuration.deploymentStrategy(null, properties));
    }

    @Test
    void 알_수_없는_전략_이름은_거절한다() {
        DeployProperties properties = new DeployProperties();
        properties.setStrategy("rolling");
        assertThrows(IllegalArgumentException.class,
                () -> configuration.deploymentStrategy(null, properties));
    }

    @Test
    void 팀원_모듈이_없으면_기본_구현을_만든다() {
        assertInstanceOf(NoopDatabaseProvisioner.class, configuration.databaseProvisioner());
        assertInstanceOf(Slf4jDeployLog.class, configuration.deployLog());
        assertInstanceOf(NoopDeployMonitor.class, configuration.deployMonitor());
        assertInstanceOf(NginxIngressRouter.class, configuration.trafficRouter(null));
    }
}
