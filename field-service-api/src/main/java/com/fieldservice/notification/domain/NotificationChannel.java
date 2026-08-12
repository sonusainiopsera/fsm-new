package com.fieldservice.notification.domain;

/**
 * Delivery channels through which notifications can be sent to users.
 */
public enum NotificationChannel {

    /** Notification delivered via email. */
    EMAIL,

    /** Notification delivered via SMS text message. */
    SMS,

    /** Notification delivered within the field service web or mobile application. */
    IN_APP,

    /** Notification delivered via mobile push notification. */
    PUSH
}
