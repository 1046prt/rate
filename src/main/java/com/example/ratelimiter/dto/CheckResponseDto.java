package com.example.ratelimiter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response payload for rate‑limit check.
 */
public class CheckResponseDto {
    @Schema(description = "true when the request is allowed, false when throttled", example = "true")
    private boolean allowed;

    @Schema(description = "Human-readable decision", example = "Request allowed")
    private String message;

    public CheckResponseDto() {}

    public CheckResponseDto(boolean allowed, String message) {
        this.allowed = allowed;
        this.message = message;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
