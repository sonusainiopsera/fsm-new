package com.fieldservice.aigateway.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ai")
class AiGatewayProperties {

    private Copilot copilot = new Copilot();
    private Provider provider = new Provider();
    private Resilience resilience = new Resilience();
    private Metrics metrics = new Metrics();

    public Copilot getCopilot() { return copilot; }
    public void setCopilot(Copilot copilot) { this.copilot = copilot; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Resilience getResilience() { return resilience; }
    public void setResilience(Resilience resilience) { this.resilience = resilience; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    static class Copilot {
        private boolean enabled = false;
        private int dailyCapPerUser = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getDailyCapPerUser() { return dailyCapPerUser; }
        public void setDailyCapPerUser(int dailyCapPerUser) { this.dailyCapPerUser = dailyCapPerUser; }
    }

    static class Provider {
        private String endpoint = "";
        private String apiKeyEnv = "AI_PROVIDER_API_KEY";
        private List<String> allowedHosts = List.of();
        private String model = "gpt-4o";
        private int maxTokens = 2000;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }

        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }

    static class Resilience {
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 20;
        private Duration waitDurationOpen = Duration.ofSeconds(30);
        private int halfOpenCalls = 3;
        private int maxConcurrentCalls = 16;
        private Duration timeLimitDuration = Duration.ofSeconds(10);

        public int getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(int v) { this.failureRateThreshold = v; }

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }

        public Duration getWaitDurationOpen() { return waitDurationOpen; }
        public void setWaitDurationOpen(Duration v) { this.waitDurationOpen = v; }

        public int getHalfOpenCalls() { return halfOpenCalls; }
        public void setHalfOpenCalls(int v) { this.halfOpenCalls = v; }

        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int v) { this.maxConcurrentCalls = v; }

        public Duration getTimeLimitDuration() { return timeLimitDuration; }
        public void setTimeLimitDuration(Duration v) { this.timeLimitDuration = v; }
    }

    static class Metrics {
        private double tokenCostPerThousand = 0.00003;

        public double getTokenCostPerThousand() { return tokenCostPerThousand; }
        public void setTokenCostPerThousand(double v) { this.tokenCostPerThousand = v; }
    }
}
