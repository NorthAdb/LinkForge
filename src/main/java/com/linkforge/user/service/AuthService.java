package com.linkforge.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.security.JwtService;
import com.linkforge.user.dto.LoginRequest;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantMapper tenantMapper;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

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
}
