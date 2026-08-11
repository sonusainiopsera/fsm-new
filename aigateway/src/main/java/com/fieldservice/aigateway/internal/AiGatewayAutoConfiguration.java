package com.fieldservice.aigateway.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring configuration wiring all internal AI gateway components.
 *
 * <p>FeatureFlagGuard is registered as the {@code @Primary} AiGatewayPort by its own
 * {@code @Component} annotation; this class wires its dependencies.
 */
@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiGatewayAutoConfiguration {

    @Bean
    EgressAllowList egressAllowList(AiGatewayProperties props) {
        return new EgressAllowList(props.getProvider().getAllowedHosts());
    }

    @Bean
    SecretsProvider aiSecretsProvider(AiGatewayProperties props) {
        return new EnvironmentSecretsProvider(props.getProvider().getApiKeyEnv());
    }

    @Bean
    AiGatewayResilienceConfig aiGatewayResilienceConfig(AiGatewayProperties props) {
        return new AiGatewayResilienceConfig(props);
    }

    @Bean
    AiGatewayMetrics aiGatewayMetrics(MeterRegistry meterRegistry, AiGatewayProperties props) {
        return new AiGatewayMetrics(
                meterRegistry,
                "configured-provider",
                props.getMetrics().getTokenCostPerThousand());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService aiVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-gateway-", 0).factory());
    }

    @Bean
    RestClient aiProviderRestClient(AiGatewayProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getProvider().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getProvider().getReadTimeout().toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Bean
    HttpAiProviderAdapter httpAiProviderAdapter(
            RestClient aiProviderRestClient,
            EgressAllowList egressAllowList,
            SecretsProvider aiSecretsProvider,
            AiGatewayResilienceConfig aiGatewayResilienceConfig,
            AiGatewayMetrics aiGatewayMetrics,
            AiGatewayProperties props,
            ExecutorService aiVirtualThreadExecutor) {
        return new HttpAiProviderAdapter(
                aiProviderRestClient,
                egressAllowList,
                aiSecretsProvider,
                aiGatewayResilienceConfig,
                aiGatewayMetrics,
                props,
                aiVirtualThreadExecutor);
    }

    @Bean
    RedisUsageCapService redisUsageCapService(StringRedisTemplate stringRedisTemplate,
                                               AiGatewayProperties props) {
        return new RedisUsageCapService(
                stringRedisTemplate,
                props.getCopilot().getDailyCapPerUser(),
                Clock.systemUTC());
    }
}
