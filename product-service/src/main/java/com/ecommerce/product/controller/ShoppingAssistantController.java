package com.ecommerce.product.controller;

import com.ecommerce.product.dto.AssistantChatRequest;
import com.ecommerce.product.dto.AssistantChatResponse;
import com.ecommerce.product.service.ShoppingAssistantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/assistant")
public class ShoppingAssistantController {
    private final ShoppingAssistantService shoppingAssistantService;

    public ShoppingAssistantController(ShoppingAssistantService shoppingAssistantService) {
        this.shoppingAssistantService = shoppingAssistantService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AssistantChatResponse> chat(
            @Valid @RequestBody AssistantChatRequest request,
            @RequestHeader(value = "X-Authenticated-User", required = false) String authenticatedUser) {
        return ResponseEntity.ok(shoppingAssistantService.chat(request, authenticatedUser));
    }
}
