package com.linkforge.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MVP auth strategy: fixed bootstrap tenant + user (no public register).
 * Default: tenant=demo / username=admin / password=admin123
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapDataLoader implements ApplicationRunner {

    private final LinkForgeProperties properties;
    private final TenantMapper tenantMapper;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getBootstrap().isEnabled()) {
            return;
        }
        String tenantName = properties.getBootstrap().getTenantName();
        String username = properties.getBootstrap().getUsername();
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>().eq(Tenant::getName, tenantName));
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setName(tenantName);
            tenant.setStatus(1);
            tenant.setCreatedAt(LocalDateTime.now());
            tenant.setUpdatedAt(LocalDateTime.now());
            tenantMapper.insert(tenant);
            log.info("bootstrap tenant created: {}", tenantName);
        }
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getTenantId, tenant.getId())
                .eq(UserAccount::getUsername, username));
        if (user == null) {
            user = new UserAccount();
            user.setTenantId(tenant.getId());
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(properties.getBootstrap().getPassword()));
            user.setStatus(1);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            userAccountMapper.insert(user);
            log.info("bootstrap user created: {}@{}", username, tenantName);
        }
    }
}
