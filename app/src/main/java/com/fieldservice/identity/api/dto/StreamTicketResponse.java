package com.fieldservice.identity.api.dto;

/**
 * Response body for the stream ticket issuance endpoint.
 *
 * <p><strong>Security note:</strong> the {@code ticket} field is an opaque, single-use value
 * that must be treated as a credential. The client must use it within {@code expiresIn} seconds
 * and must not log, store, or share it.
 *
 * @param ticket    opaque 256-bit base64url-encoded ticket value
 * @param expiresIn seconds until the ticket expires (always 60)
 */
public record StreamTicketResponse(String ticket, int expiresIn) {}
