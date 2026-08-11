package com.fieldservice.aigateway;

import com.fieldservice.aigateway.api.AiGatewayPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Test configuration that registers {@link FakeAiGatewayAdapter} as the primary
 * {@link AiGatewayPort} bean in the {@code test} Spring profile.
 *
 * <p>Import into test classes that need the AI gateway bean via
 * {@code @Import(FakeAiGatewayConfiguration.class)}, or rely on automatic discovery
 * when running under the {@code test} profile.
 */
@TestConfiguration
@Profile("test")
public class FakeAiGatewayConfiguration {

    @Bean
    @Primary
    public AiGatewayPort aiGatewayPort() {
        return new FakeAiGatewayAdapter();
    }
}
