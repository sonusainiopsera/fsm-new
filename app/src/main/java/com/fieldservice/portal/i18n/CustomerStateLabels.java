package com.fieldservice.portal.i18n;

import com.fieldservice.workorder.domain.WorkOrderStatus;

/**
 * Table-driven mapping from work order lifecycle state (and optional hold reason code)
 * to approved customer-facing plain-language labels.
 *
 * <p>No internal jargon, raw enum names, technical codes, or dispatch scores may appear
 * in the output. Every lifecycle state including every ON_HOLD reason code has a dedicated
 * entry with approved wording (BR-33 / BR-34).
 *
 * <p>Both the API response and the web surface reuse these labels via the static
 * {@link #resolve(WorkOrderStatus, String)} lookup so wording is never duplicated.
 */
public enum CustomerStateLabels {

    NEW(
            "Request received",
            "We have received your request and it is awaiting assignment to a field engineer."
    ),
    ASSIGNED(
            "Engineer assigned",
            "A field engineer has been assigned to your request and will be in touch shortly to arrange a visit."
    ),
    EN_ROUTE(
            "Engineer on the way",
            "Your field engineer is travelling to your location now."
    ),
    IN_PROGRESS(
            "Work in progress",
            "Your field engineer is on site and working on your request."
    ),
    ON_HOLD_DEFAULT(
            "Work temporarily paused",
            "Work on your request has been temporarily paused. We will be in touch with an update shortly."
    ),
    ON_HOLD_AWAITING_PARTS(
            "Waiting for parts",
            "Work has been paused while we wait for parts to arrive. We will resume as soon as they are available."
    ),
    ON_HOLD_CUSTOMER_UNAVAILABLE(
            "Waiting for access",
            "Our engineer was unable to gain access. Please contact us to rearrange your appointment."
    ),
    ON_HOLD_ACCESS_DENIED(
            "Waiting for access",
            "Our engineer could not access the site. Please contact us so we can arrange access."
    ),
    ON_HOLD_WEATHER(
            "Paused due to weather conditions",
            "Work has been paused due to adverse weather conditions. We will resume as soon as it is safe to do so."
    ),
    ON_HOLD_SAFETY_CONCERN(
            "Paused while a safety matter is addressed",
            "Work has been paused while a safety concern is being addressed. Our team will update you once work can safely resume."
    ),
    ON_HOLD_AWAITING_APPROVAL(
            "Awaiting your approval",
            "Work has been paused and we need your approval before we can continue. Please contact us."
    ),
    ON_HOLD_LEGACY_OTHER(
            "Work temporarily paused",
            "Work on your request has been temporarily paused. We will be in touch shortly."
    ),
    COMPLETED(
            "Work completed",
            "The field engineer has finished work on your request. Please contact us if you have any concerns or questions."
    ),
    CLOSED(
            "Service request closed",
            "Your service request has been closed. Thank you for choosing our service."
    ),
    CANCELLED(
            "Service request cancelled",
            "Your service request has been cancelled. Please contact us if you need further assistance."
    );

    private final String label;
    private final String description;

    CustomerStateLabels(String label, String description) {
        this.label       = label;
        this.description = description;
    }

    public String getLabel()       { return label; }
    public String getDescription() { return description; }

    /**
     * Resolves the appropriate customer-facing label for the given work order state.
     *
     * @param state          the work order lifecycle state (never null)
     * @param holdReasonCode the active hold reason code when state is {@code ON_HOLD};
     *                       ignored for all other states; may be null
     * @return the matching label entry; never null
     */
    public static CustomerStateLabels resolve(WorkOrderStatus state, String holdReasonCode) {
        if (state == WorkOrderStatus.ON_HOLD) {
            return resolveHoldLabel(holdReasonCode);
        }
        return switch (state) {
            case NEW         -> NEW;
            case ASSIGNED    -> ASSIGNED;
            case EN_ROUTE    -> EN_ROUTE;
            case IN_PROGRESS -> IN_PROGRESS;
            case COMPLETED   -> COMPLETED;
            case CLOSED      -> CLOSED;
            case CANCELLED   -> CANCELLED;
            case ON_HOLD     -> ON_HOLD_DEFAULT; // covered above, unreachable
        };
    }

    private static CustomerStateLabels resolveHoldLabel(String code) {
        if (code == null) {
            return ON_HOLD_DEFAULT;
        }
        return switch (code) {
            case "AWAITING_PARTS"       -> ON_HOLD_AWAITING_PARTS;
            case "CUSTOMER_UNAVAILABLE" -> ON_HOLD_CUSTOMER_UNAVAILABLE;
            case "ACCESS_DENIED"        -> ON_HOLD_ACCESS_DENIED;
            case "WEATHER"              -> ON_HOLD_WEATHER;
            case "SAFETY_CONCERN"       -> ON_HOLD_SAFETY_CONCERN;
            case "AWAITING_APPROVAL"    -> ON_HOLD_AWAITING_APPROVAL;
            default                     -> ON_HOLD_LEGACY_OTHER;
        };
    }
}
