package com.fieldservice.identity.api.dto;

/**
 * Response body for {@code POST /api/v1/auth/stream-ticket}.
 *
 * <p>{@code ticket} is the opaque single-use value to present as the {@code ticket}
 * query parameter when opening an SSE stream. It must not be persisted, logged, or
 * sent over an unencrypted channel.
 *
 * <p>{@code expiresIn} is always 60 (seconds); the ticket is useless after that.
 */
public record StreamTicketResponse(String ticket, int expiresIn) {}
