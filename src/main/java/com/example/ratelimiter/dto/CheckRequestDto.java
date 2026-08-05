package com.example.ratelimiter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for rate‑limit check.
 */
public class CheckRequestDto {
    @NotBlank(message = "clientId must not be blank")
    @Schema(description = "Identifier of the client whose limit is being checked", example = "user123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String clientId;

    @NotBlank(message = "algorithm must not be blank")
    @Schema(description = "Rate-limiting algorithm to apply", example = "FIXED",
            allowableValues = {"FIXED", "SLIDING_LOG", "SLIDING_COUNTER", "TOKEN_BUCKET", "LEAKY_BUCKET"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String algorithm; // e.g. "FIXED", "SLIDING_LOG", etc.

    public CheckRequestDto() {}

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }
}
