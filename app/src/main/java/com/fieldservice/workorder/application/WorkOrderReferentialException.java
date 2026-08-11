package com.fieldservice.workorder.application;

/**
 * Thrown when a work order creation request fails referential consistency validation:
 * e.g. the site does not belong to the requested customer, or the asset is not at the
 * requested site. Maps to HTTP 422 via WorkOrderExceptionHandler.
 */
public class WorkOrderReferentialException extends RuntimeException {

    private final String code;
    private final String field;

    public WorkOrderReferentialException(String code, String field, String message) {
        super(message);
        this.code  = code;
        this.field = field;
    }

    public String getCode()  { return code; }
    public String getField() { return field; }
}
