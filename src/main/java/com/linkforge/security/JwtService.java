package com.linkforge.security;

import com.linkforge.config.LinkForgeProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expireHours;

    public JwtService(LinkForgeProperties properties) {
        byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireHours = properties.getJwt().getExpireHours();
    }

    public String createToken(Long userId, Long tenantId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
