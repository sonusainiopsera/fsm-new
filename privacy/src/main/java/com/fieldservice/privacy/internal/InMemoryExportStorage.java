package com.fieldservice.privacy.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExportStoragePort} for development and tests.
 *
 * <p>Active when no other {@link ExportStoragePort} bean is registered.
 * Production environments should provide an S3-backed implementation that
 * replaces this bean via {@link ConditionalOnMissingBean}.
 *
 * <p>Data is held in a JVM-local map; it does not survive process restarts
 * and is not visible across replicas. Not suitable for production use.
 */
@Component
@ConditionalOnMissingBean(value = ExportStoragePort.class, ignored = InMemoryExportStorage.class)
class InMemoryExportStorage implements ExportStoragePort {

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public String store(String key, byte[] content) {
        store.put(key, content);
        return key;
    }

    @Override
    public byte[] retrieve(String key) {
        return store.get(key);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public String generateDownloadUrl(String key, int expirySeconds) {
        return "/api/v1/privacy/dsar-exports/download?key=" + key
                + "&expires=" + (System.currentTimeMillis() / 1000 + expirySeconds);
    }
}
