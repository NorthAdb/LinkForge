package com.linkforge.user.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.user.dto.CreateInvitationRequest;
import com.linkforge.user.dto.InvitationCreatedResponse;
import com.linkforge.user.dto.InvitationView;
import com.linkforge.user.dto.MemberView;
import com.linkforge.user.dto.ResetPasswordResponse;
import com.linkforge.user.dto.UpdateMemberRequest;
import com.linkforge.user.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Batch 2b team surface. Everything here sits under anyRequest().hasRole("USER")
 * at the filter chain (JWT only — API keys get a chain-level 403), and the
 * ADMIN + own-tenant gates live in TeamService (a MEMBER caller is a 403, a
 * foreign tenant's id a 404).
 */
@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping("/invitations")
    public ApiResponse<InvitationCreatedResponse> createInvitation(
            @Valid @RequestBody CreateInvitationRequest request) {
        return ApiResponse.ok(teamService.createInvitation(request));
    }

    @GetMapping("/invitations")
    public ApiResponse<List<InvitationView>> listInvitations() {
        return ApiResponse.ok(teamService.listInvitations());
    }

    @DeleteMapping("/invitations/{id}")
    public ApiResponse<Void> revokeInvitation(@PathVariable Long id) {
        teamService.revokeInvitation(id);
        return ApiResponse.ok();
    }

    @GetMapping("/members")
    public ApiResponse<List<MemberView>> listMembers() {
        return ApiResponse.ok(teamService.listMembers());
    }

    @PatchMapping("/members/{userId}")
    public ApiResponse<MemberView> updateMember(@PathVariable Long userId,
                                                @RequestBody UpdateMemberRequest request) {
        return ApiResponse.ok(teamService.updateMember(userId, request));
    }

    @DeleteMapping("/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable Long userId) {
        teamService.removeMember(userId);
        return ApiResponse.ok();
    }

    @PostMapping("/members/{userId}/reset-password")
    public ApiResponse<ResetPasswordResponse> resetPassword(@PathVariable Long userId) {
        return ApiResponse.ok(teamService.resetPassword(userId));
    }
}
