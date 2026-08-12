package com.fieldservice.workforce.internal;

public class NoActiveJobException extends RuntimeException {
    public NoActiveJobException(String technicianId) {
        super("Technician " + technicianId + " has no work order in EN_ROUTE or IN_PROGRESS state");
    }
}
