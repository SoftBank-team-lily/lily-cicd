package com.lily.cicd.config;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.HttpDatabaseProvisioner;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** provisioner-url 유무에 따라 어떤 DB 모듈이 주입되는지 */
class DatabaseModuleConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class))
            .withBean(DeployProperties.class)
            .withUserConfiguration(ModuleConfiguration.class, DatabaseModuleConfiguration.class);

    @Test
    void url_이_없으면_기존_Noop() {
        runner.run(context -> assertThat(context.getBean(DatabaseProvisioner.class))
                .isInstanceOf(NoopDatabaseProvisioner.class));
    }

    @Test
    void url_이_있으면_프로비저너_호출() {
        runner.withPropertyValues("lily.database.provisioner-url=http://db-provisioner.lily-system.svc",
                        "lily.database.api-token=token")
                .run(context -> assertThat(context.getBean(DatabaseProvisioner.class))
                        .isInstanceOf(HttpDatabaseProvisioner.class));
    }
}
