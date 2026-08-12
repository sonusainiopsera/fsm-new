package com.fieldservice.portal.csat;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Attempts notification delivery for a newly issued CSAT survey.
 *
 * <p>Failure is always non-fatal: the survey remains answerable in-app regardless
 * of delivery outcome. When no {@link NotificationPort} is available (non-worker
 * profile), the delivery status is set to {@link CsatDeliveryStatus#IN_APP}.
 */
@Component
class CsatSurveyDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(CsatSurveyDeliveryService.class);

    private final NotificationPort notificationPort;

    CsatSurveyDeliveryService(
            @Autowired(required = false) NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    /**
     * Attempts delivery and updates {@code survey.deliveryStatus} accordingly.
     * Never throws — any exception is caught, logged, and mapped to FAILED.
     */
    void attemptDelivery(CsatSurvey survey) {
        if (notificationPort == null) {
            survey.setDeliveryStatus(CsatDeliveryStatus.IN_APP);
            return;
        }
        try {
            NotificationRequest req = new NotificationRequest(
                    UuidV7.generate(),
                    NotificationChannel.EMAIL,
                    survey.getAccountId(),
                    null,
                    "Your feedback matters — rate your recent service visit",
                    "Please take a moment to complete a short survey for your recent service request.",
                    "CSAT_SURVEY",
                    "LOW");

            DeliveryOutcome outcome = notificationPort.send(req);
            if (outcome == DeliveryOutcome.SENT) {
                survey.setDeliveryStatus(CsatDeliveryStatus.SENT);
            } else {
                log.warn("csat_delivery_non_sent survey_id={} outcome={}", survey.getId(), outcome);
                survey.setDeliveryStatus(CsatDeliveryStatus.FAILED);
            }
        } catch (Exception e) {
            log.warn("csat_delivery_failed survey_id={}", survey.getId(), e);
            survey.setDeliveryStatus(CsatDeliveryStatus.FAILED);
        }
    }
}
