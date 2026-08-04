package com.example.ratelimiter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for rate‑limit check.
 */
public class CheckRequestDto {
    @NotBlank(message = "clientId must not be blank")
    private String clientId;
    @NotBlank(message = "algorithm must not be blank")
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
