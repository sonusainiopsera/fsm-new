package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.platform.privacy.FreeTextScrubber;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;

/**
 * Wires all AI gateway beans. Package-private — the public surface is {@link AiGatewayPort} only.
 */
@Configuration
@EnableConfigurationProperties(AiGatewayProperties.class)
public class AiGatewayAutoConfiguration {

    // ---- Resilience4j beans ---------------------------------------------------

    @Bean
    CircuitBreaker aiCircuitBreaker(AiGatewayProperties props) {
        return AiGatewayResilienceConfig.circuitBreaker(props);
    }

    @Bean
    Bulkhead aiGatewayBulkhead(AiGatewayProperties props) {
        return AiGatewayResilienceConfig.bulkhead(props);
    }

    @Bean
    TimeLimiter aiTimeLimiter(AiGatewayProperties props) {
        return AiGatewayResilienceConfig.timeLimiter(props);
    }

    @Bean
    Retry aiRetry(AiGatewayProperties props) {
        return AiGatewayResilienceConfig.retry(props);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService aiGatewayExecutor() {
        return AiGatewayResilienceConfig.virtualThreadExecutor();
    }

    // ---- HTTP adapter ---------------------------------------------------------

    @Bean
    RestClient aiProviderRestClient(AiGatewayProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.provider().connectTimeout().toMillis());
        factory.setReadTimeout((int) props.provider().readTimeout().toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Bean
    SecretsProvider aiSecretsProvider(AiGatewayProperties props) {
        return new EnvironmentSecretsProvider(props);
    }

    @Bean
    HttpAiProviderAdapter httpAiProviderAdapter(
            RestClient aiProviderRestClient,
            AiGatewayProperties props,
            EgressAllowList egressAllowList,
            SecretsProvider aiSecretsProvider,
            AiGatewayMetrics metrics,
            CircuitBreaker aiCircuitBreaker,
            Bulkhead aiGatewayBulkhead,
            TimeLimiter aiTimeLimiter,
            Retry aiRetry,
            ExecutorService aiGatewayExecutor) {
        return new HttpAiProviderAdapter(
                aiProviderRestClient, props, egressAllowList, aiSecretsProvider,
                metrics, aiCircuitBreaker, aiGatewayBulkhead, aiTimeLimiter, aiRetry, aiGatewayExecutor);
    }

    // ---- Usage cap -----------------------------------------------------------

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(UsageCapService.class)
    UsageCapService redisUsageCapService(StringRedisTemplate stringRedisTemplate) {
        return new RedisUsageCapService(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(UsageCapService.class)
    UsageCapService noOpUsageCapService() {
        return new NoOpUsageCapService();
    }

    // ---- Primary port (feature-flag-guarded) ---------------------------------

    @Bean
    @ConditionalOnMissingBean(AiGatewayPort.class)
    AiGatewayPort aiGatewayPort(
            HttpAiProviderAdapter httpAiProviderAdapter,
            UsageCapService usageCapService,
            AiGatewayProperties props,
            FreeTextScrubber freeTextScrubber) {
        AiGatewayPort base = new FeatureFlagGuardedGateway(httpAiProviderAdapter, usageCapService, props);
        return new RedactingAiGatewayAdapter(base, freeTextScrubber);
    }
}
