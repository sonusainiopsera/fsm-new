package com.fieldservice.notification.internal.adapter;

import com.fieldservice.notification.api.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * No-op adapter used when {@code notification.provider=NONE} (the default).
 * Always succeeds, allowing the platform to boot without an external provider.
 */
class StubExternalNotificationAdapter implements ExternalNotificationAdapter {

    private static final Logger log = LoggerFactory.getLogger(StubExternalNotificationAdapter.class);

    @Override
    public String send(NotificationRequest request) {
        log.debug("notification_stub_send channel={} recipient_mask_len={}",
                request.channel(), request.recipientContact() != null ? "present" : "absent");
        return "stub-" + UUID.randomUUID();
    }

    @Override
    public String adapterName() { return "STUB"; }
}
