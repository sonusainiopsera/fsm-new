package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op adapter used when {@code notification.provider=NONE} (the default).
 * Logs the send request and returns {@code null} as the provider reference,
 * which the dispatcher records as an IN_APP delivery.
 */
class StubExternalNotificationAdapter implements ExternalNotificationAdapter {

    private static final Logger log = LoggerFactory.getLogger(StubExternalNotificationAdapter.class);

    @Override
    public String adapterName() {
        return "stub";
    }

    @Override
    public String send(NotificationRequest request, String maskedRecipient) {
        log.debug("notification_stub send channel={} eventId={} recipient={}",
                request.channel(), request.eventId(), maskedRecipient);
        return null;
    }
}
