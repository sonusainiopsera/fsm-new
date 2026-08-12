package com.fieldservice.privacy.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ErasureRequest(
        @NotNull UUID dsarRequestId,
        @NotNull String confirmation,
        String note) {}
