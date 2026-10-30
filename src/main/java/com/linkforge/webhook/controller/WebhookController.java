package com.linkforge.webhook.controller;

import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.webhook.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook endpoint configuration, JWT only (hasRole USER): a leaked API key must
 * not be able to re-point a tenant's callbacks (D-Bw2-4/16).
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PutMapping("/endpoint")
    public ApiResponse<Map<String, Object>> upsert(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(webhookService.upsert(request, requireUser()));
    }

    @GetMapping("/endpoint")
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(webhookService.get(requireUser()));
    }

    @PostMapping("/endpoint/rotate-secret")
    public ApiResponse<Map<String, Object>> rotateSecret() {
        return ApiResponse.ok(webhookService.rotateSecret(requireUser()));
    }

    @DeleteMapping("/endpoint")
    public ApiResponse<Void> delete() {
        webhookService.delete(requireUser());
        return ApiResponse.ok(null);
    }

    private static AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        return user;
    }
}
