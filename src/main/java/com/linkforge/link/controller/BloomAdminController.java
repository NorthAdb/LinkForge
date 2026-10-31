package com.linkforge.link.controller;

import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.link.support.CodeBloomFilter;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * C2 wave B (D-C2-12): manual Bloom rebuild trigger. JWT only — no SecurityConfig
 * entry, anyRequest().hasRole("USER") already 403s API keys at the chain. The ADMIN
 * gate mirrors ApiKeyService.create; the trigger rebuilds this process's filter only.
 */
@RestController
@RequestMapping("/api/admin/bloom")
@RequiredArgsConstructor
public class BloomAdminController {

    private final UserAccountMapper userAccountMapper;
    private final CodeBloomFilter codeBloomFilter;

    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Object>> rebuild() {
        AuthUser user = requireUser();
        UserAccount account = userAccountMapper.selectById(user.getUserId());
        // pre-V8 rows have a null role, which reads as MEMBER
        if (account == null || !"ADMIN".equals(account.getTenantRole())) {
            throw new BizException(403, "only a tenant ADMIN can trigger a bloom rebuild");
        }
        long codes = codeBloomFilter.rebuildManual();
        return ApiResponse.ok(Map.of("result", "ok", "codes", codes));
    }

    private static AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        return user;
    }
}
