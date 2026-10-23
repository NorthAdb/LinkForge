package com.linkforge.user.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.user.dto.LoginRequest;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.dto.RegisterRequest;
import com.linkforge.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.register(request, httpRequest));
    }
}
