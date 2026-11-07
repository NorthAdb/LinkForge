package com.linkforge.user.controller;

import com.linkforge.auth.SessionService;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.user.dto.AcceptInvitationRequest;
import com.linkforge.user.dto.ChangePasswordRequest;
import com.linkforge.user.dto.LoginRequest;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.dto.LogoutRequest;
import com.linkforge.user.dto.RefreshRequest;
import com.linkforge.user.dto.RegisterRequest;
import com.linkforge.user.service.AuthService;
import com.linkforge.user.service.TeamService;
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
    private final SessionService sessionService;
    private final TeamService teamService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest);
        response.setRefreshToken(sessionService.startSession(response.getUserId(), httpRequest));
        return ApiResponse.ok(response);
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletRequest httpRequest) {
        LoginResponse response = authService.register(request, httpRequest);
        response.setRefreshToken(sessionService.startSession(response.getUserId(), httpRequest));
        return ApiResponse.ok(response);
    }

    /** permitAll by design (batch 2b): the invitation token IS the credential.
     * The response is a full login response — accepting an invitation creates
     * the account already signed in. */
    @PostMapping("/accept-invitation")
    public ApiResponse<LoginResponse> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request,
                                                       HttpServletRequest httpRequest) {
        return ApiResponse.ok(teamService.acceptInvitation(request, httpRequest));
    }

    /** permitAll by design: the refresh token IS the credential (the access token
     * is typically expired — that is why the client is here). */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.ok(sessionService.refresh(request.getRefreshToken(), httpRequest));
    }

    /** permitAll by design: an expired access token must not trap the user in a
     * session they cannot close; the refresh token is the credential. */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request,
                                    HttpServletRequest httpRequest) {
        sessionService.logout(request.getRefreshToken(), httpRequest);
        return ApiResponse.ok();
    }

    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll() {
        sessionService.logoutAll(requireUser().getUserId());
        return ApiResponse.ok();
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        sessionService.changePassword(requireUser().getUserId(),
                request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.ok();
    }

    /** /api/auth/** is permitAll at the filter chain, so the endpoints that do
     * need a caller enforce it here (an absent UserContext means the JWT filter
     * set no authentication: no token, or a token that failed its DB recheck). */
    private AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        return user;
    }
}
