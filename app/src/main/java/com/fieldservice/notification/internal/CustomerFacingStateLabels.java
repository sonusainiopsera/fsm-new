package com.fieldservice.notification.internal;

import java.util.Map;
import java.util.Optional;

/**
 * Approved customer-facing labels for work order lifecycle states.
 *
 * <p>Internal state names (e.g. {@code "IN_PROGRESS"}) are never exposed on customer channels.
 * All notification consumers that send customer-visible messages must use this map.
 * Hold states with a reason code use the compound key {@code "ON_HOLD:<reasonCode>"}.
 */
public final class CustomerFacingStateLabels {

    private CustomerFacingStateLabels() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("NEW",                           "Request received"),
            Map.entry("ASSIGNED",                      "Engineer assigned"),
            Map.entry("EN_ROUTE",                      "Engineer on the way"),
            Map.entry("IN_PROGRESS",                   "Work in progress"),
            Map.entry("ON_HOLD",                       "Work temporarily paused"),
            Map.entry("ON_HOLD:AWAITING_PARTS",        "Waiting for parts"),
            Map.entry("ON_HOLD:CUSTOMER_UNAVAILABLE",  "Waiting for access"),
            Map.entry("ON_HOLD:ACCESS_DENIED",         "Waiting for access"),
            Map.entry("ON_HOLD:WEATHER",               "Paused due to weather conditions"),
            Map.entry("ON_HOLD:SAFETY_CONCERN",        "Paused while a safety matter is addressed"),
            Map.entry("ON_HOLD:AWAITING_APPROVAL",     "Awaiting your approval"),
            Map.entry("COMPLETED",                     "Work completed"),
            Map.entry("CLOSED",                        "Service request closed"),
            Map.entry("CANCELLED",                     "Service request cancelled")
    );

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("NEW",                           "We have received your request and it is awaiting assignment to a field engineer."),
            Map.entry("ASSIGNED",                      "A field engineer has been assigned to your request and will be in touch shortly to arrange a visit."),
            Map.entry("EN_ROUTE",                      "Your field engineer is travelling to your location now."),
            Map.entry("IN_PROGRESS",                   "Your field engineer is on site and working on your request."),
            Map.entry("ON_HOLD",                       "Work on your request has been temporarily paused. We will be in touch with an update shortly."),
            Map.entry("ON_HOLD:AWAITING_PARTS",        "Work has been paused while we wait for parts to arrive. We will resume as soon as they are available."),
            Map.entry("ON_HOLD:CUSTOMER_UNAVAILABLE",  "Our engineer was unable to gain access. Please contact us to rearrange your appointment."),
            Map.entry("ON_HOLD:ACCESS_DENIED",         "Our engineer could not access the site. Please contact us so we can arrange access."),
            Map.entry("ON_HOLD:WEATHER",               "Work has been paused due to adverse weather conditions. We will resume as soon as it is safe to do so."),
            Map.entry("ON_HOLD:SAFETY_CONCERN",        "Work has been paused while a safety concern is being addressed. Our team will update you once work can safely resume."),
            Map.entry("ON_HOLD:AWAITING_APPROVAL",     "Work has been paused and we need your approval before we can continue. Please contact us."),
            Map.entry("COMPLETED",                     "The field engineer has finished work on your request. Please contact us if you have any concerns or questions."),
            Map.entry("CLOSED",                        "Your service request has been closed. Thank you for choosing our service."),
            Map.entry("CANCELLED",                     "Your service request has been cancelled. Please contact us if you need further assistance.")
    );

    /**
     * Returns the customer-facing label for the given internal state.
     * For {@code ON_HOLD} states, pass {@code holdReasonCode} to get the specific label;
     * if null or unrecognised the generic hold label is returned.
     *
     * @param internalState  internal work order state name (e.g. "IN_PROGRESS")
     * @param holdReasonCode hold reason code (e.g. "AWAITING_PARTS"); may be null
     * @return customer-facing label, or the original state name if unmapped (safety net)
     */
    public static String label(String internalState, String holdReasonCode) {
        return lookup(LABELS, internalState, holdReasonCode)
                .orElse(internalState);
    }

    /**
     * Returns the customer-facing description for the given internal state.
     */
    public static String description(String internalState, String holdReasonCode) {
        return lookup(DESCRIPTIONS, internalState, holdReasonCode)
                .orElse("");
    }

    private static Optional<String> lookup(Map<String, String> map,
                                            String state, String holdReasonCode) {
        if ("ON_HOLD".equals(state) && holdReasonCode != null) {
            String specific = map.get("ON_HOLD:" + holdReasonCode);
            if (specific != null) {
                return Optional.of(specific);
            }
        }
        return Optional.ofNullable(map.get(state));
    }
}
