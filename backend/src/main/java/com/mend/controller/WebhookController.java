package com.mend.controller;

import com.mend.dto.WebhookResponseDto;
import com.mend.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<WebhookResponseDto> handleRazorpayWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody(required = false) String rawPayload) {

        WebhookResponseDto response = webhookService.processRazorpayWebhook(rawPayload, signature);
        return ResponseEntity.ok(response);
    }
}
