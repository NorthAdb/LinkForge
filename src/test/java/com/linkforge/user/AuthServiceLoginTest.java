package com.linkforge.user;

import com.linkforge.common.exception.BizException;
import com.linkforge.common.exception.RateLimitedException;
import com.linkforge.common.ratelimit.RateLimitDecision;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.JwtService;
import com.linkforge.user.dto.LoginRequest;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import com.linkforge.user.service.AuthService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit H1 companion: every failed login must cost exactly one BCrypt
 * comparison, including unknown tenant, unknown user and disabled user —
 * otherwise response timing enumerates accounts. Rate-limit rejection must
 * short-circuit before any credential work.
 */
class AuthServiceLoginTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final LinkForgeProperties properties = mock(LinkForgeProperties.class);
    private final AuthService authService = new AuthService(tenantMapper, userAccountMapper,
            passwordEncoder, mock(JwtService.class), rateLimiter, properties, new SimpleMeterRegistry());
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final LoginRequest request = new LoginRequest();

    @BeforeEach
    void setUp() {
        when(properties.getRateLimit()).thenReturn(new LinkForgeProperties.RateLimit());
        when(rateLimiter.acquire(anyString(), anyInt(), anyInt()))
                .thenReturn(new RateLimitDecision(true, 0, 1));
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        request.setTenantName("demo");
        request.setUsername("admin");
        request.setPassword("whatever-123456");
    }

    private Tenant activeTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("demo");
        tenant.setStatus(1);
        return tenant;
    }

    @Test
    void unknownTenantStillRunsBCryptOnce() {
        when(tenantMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> authService.login(request, httpRequest));
        assertEquals(401, ex.getCode());
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
        verify(userAccountMapper, never()).selectOne(any());
    }

    @Test
    void unknownUserStillRunsBCryptOnce() {
        when(tenantMapper.selectOne(any())).thenReturn(activeTenant());
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> authService.login(request, httpRequest));
        assertEquals(401, ex.getCode());
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void disabledUserStillRunsBCryptOnce() {
        when(tenantMapper.selectOne(any())).thenReturn(activeTenant());
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setTenantId(1L);
        user.setUsername("admin");
        user.setPasswordHash("stored-hash");
        user.setStatus(0);
        when(userAccountMapper.selectOne(any())).thenReturn(user);
        BizException ex = assertThrows(BizException.class, () -> authService.login(request, httpRequest));
        assertEquals(401, ex.getCode());
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void rateLimitedLoginSkipsCredentialWork() {
        when(rateLimiter.acquire(anyString(), anyInt(), anyInt()))
                .thenReturn(new RateLimitDecision(false, 42, 6));
        assertThrows(RateLimitedException.class, () -> authService.login(request, httpRequest));
        verify(tenantMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }
}
