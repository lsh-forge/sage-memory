package com.pa.sagememory.agent;

import com.pa.sagememory.agent.multimodal.VisionProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

/** Checks timeout budgets offline, without waiting minutes or contacting model providers. */
class AddTimeoutConfigurationTest {
    @Test
    void addBudgetsLeaveRoomForHttpCompletionBeforeTheCompetitionLimit() {
        var properties = new AddAgentProperties();
        properties.validate();
        assertThat(properties.getModelTimeoutSeconds()).isEqualTo(180);
        assertThat(properties.getTimeoutSeconds()).isEqualTo(600);
        assertThat(properties.getConsolidationTimeoutSeconds()).isEqualTo(300);
        assertThat(properties.getQueueTimeoutSeconds()).isEqualTo(120);
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(1740);
        assertThat(properties.getHttpWaitTimeoutSeconds()).isEqualTo(1770)
                .isGreaterThan(properties.getRequestTimeoutSeconds()).isLessThan(1800);
        assertThat(new VisionProperties().getTimeoutSeconds()).isEqualTo(180);
        assertThat(new VisionProperties().getMaxOutputTokens()).isEqualTo(10000);
    }

    @Test
    void yamlTimeoutOverridesRemainValidWithoutPrintingSecrets() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var values = yaml.getObject();
        assertThat(values).isNotNull();
        // YAML intentionally overrides Java defaults; test budget safety rather than forbidding configuration changes.
        int request = Integer.parseInt(values.getProperty("sage.memory.add.request-timeout-seconds"));
        int http = Integer.parseInt(values.getProperty("sage.memory.add.http-wait-timeout-seconds"));
        assertThat(request).isPositive().isLessThan(1800);
        assertThat(http).isGreaterThan(request).isLessThanOrEqualTo(1800);
        for (String name : new String[]{"timeout-seconds", "model-timeout-seconds", "consolidation-timeout-seconds",
                "queue-timeout-seconds", "vision.timeout-seconds"}) {
            assertThat(Integer.parseInt(values.getProperty("sage.memory.add." + name)))
                    .as(name).isPositive().isLessThanOrEqualTo(request);
        }
        assertThat(Integer.parseInt(values.getProperty("sage.memory.add.vision.max-output-tokens"))).isPositive();
    }

    @Test
    void longIndividualTimeoutsAreStillClippedToTheSharedRequestBudget() {
        var deadline = new AddDeadline(2);
        assertThat(deadline.limit(600)).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(deadline.limit(180)).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        // Search keeps its own shorter overall budget despite the shared vision timeout increase.
        var search = new SearchAgentProperties();
        assertThat(search.getRequestTimeoutSeconds()).isEqualTo(180);
        assertThat(search.getHttpWaitTimeoutSeconds()).isEqualTo(200);
    }
}
