package com.fieldservice.workforce.web;

/** Result row for a single item in the certification batch-upsert response. */
public record CertificationRowResult(
        String typeCode,
        String outcome,
        String error
) {}
