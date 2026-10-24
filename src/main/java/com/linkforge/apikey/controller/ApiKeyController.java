package com.linkforge.apikey.controller;

import com.linkforge.apikey.dto.ApiKeyDtos;
import com.linkforge.apikey.service.ApiKeyService;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API-key management, JWT only (hasRole USER): a key must never be able to mint,
 * list or revoke keys (D-Bw2-4).
 */
@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping
    public ApiResponse<ApiKeyDtos.Created> create(@Valid @RequestBody ApiKeyDtos.CreateRequest request) {
        return ApiResponse.ok(apiKeyService.create(request, requireUser()));
    }

    @GetMapping
    public ApiResponse<List<ApiKeyDtos.View>> list() {
        return ApiResponse.ok(apiKeyService.listOwn(requireUser()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable long id) {
        apiKeyService.revoke(requireUser(), id);
        return ApiResponse.ok(null);
    }

    private static AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new com.linkforge.common.exception.BizException(401, "unauthorized");
        }
        return user;
    }
}
