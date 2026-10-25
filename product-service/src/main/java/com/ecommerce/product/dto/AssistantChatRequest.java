package com.ecommerce.product.dto;

import jakarta.validation.constraints.NotBlank;

public class AssistantChatRequest {
    private String sessionId;
    private String userId;

    @NotBlank
    private String message;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
