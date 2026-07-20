package com.example.monkey.shared.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SnowflakeDeploymentContractTest {

    @Test
    void helmReplicasUsePodIdentityAndDistributedNodeLease() throws IOException {
        String podTemplate = Files.readString(Path.of("helm/monkeyshop/templates/_pod.tpl"));
        String values = Files.readString(Path.of("helm/monkeyshop/values.yaml"));

        assertThat(podTemplate)
                .contains("name: MONKEYSHOP_INSTANCE_ID")
                .contains("fieldPath: metadata.uid")
                .contains("name: APP_SNOWFLAKE_NODE_LEASE_ENABLED")
                .contains("name: APP_SNOWFLAKE_NODE_LEASE_NAMESPACE");
        assertThat(values)
                .contains("distributedNodeLease:")
                .contains("enabled: true")
                .contains("duration: PT30S")
                .contains("renewInterval: PT10S");
    }

    @Test
    void productionProfilesEnableDistributedNodeLeasesByDefault() throws IOException {
        String production = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        String staging = Files.readString(Path.of("src/main/resources/application-staging.yml"));

        assertThat(production).contains("enabled: ${APP_SNOWFLAKE_NODE_LEASE_ENABLED:true}");
        assertThat(staging).contains("enabled: ${APP_SNOWFLAKE_NODE_LEASE_ENABLED:true}");
    }
}
