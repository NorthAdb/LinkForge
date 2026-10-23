package com.linkforge.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.JwtService;
import com.linkforge.user.dto.LoginRequest;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.dto.RegisterRequest;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantMapper tenantMapper;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RedisRateLimiter rateLimiter;
    private final LinkForgeProperties properties;

    public LoginResponse login(LoginRequest request) {
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getName, request.getTenantName()));
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BizException(401, "invalid credentials");
        }
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getTenantId, tenant.getId())
                .eq(UserAccount::getUsername, request.getUsername()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "invalid credentials");
        }
        String token = jwtService.createToken(user.getId(), tenant.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), tenant.getId(), user.getUsername(), tenant.getName());
    }

    /**
     * Open registration: each request creates a brand-new tenant and its first user.
     * The unique index on tenant name is the concurrency backstop for the 409 check.
     */
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        LinkForgeProperties.RateLimit limit = properties.getRateLimit();
        String key = limit.getKeyPrefix() + "register:" + ClientIps.resolve(httpRequest, limit.isTrustXff());
        if (!rateLimiter.tryAcquire(key, limit.getRegisterMax(), limit.getRegisterWindowSeconds())) {
            throw new BizException(429, "too many requests, retry later");
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.getTenantName());
        tenant.setStatus(1);
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        try {
            tenantMapper.insert(tenant);
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "tenant name already exists");
        }

        UserAccount user = new UserAccount();
        user.setTenantId(tenant.getId());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(user);

        String token = jwtService.createToken(user.getId(), tenant.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), tenant.getId(), user.getUsername(), tenant.getName());
    }
}
