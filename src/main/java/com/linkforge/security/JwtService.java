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

    /** Legacy shape: a token version of 0 (every pre-V11 user sits at 0). */
    public String createToken(Long userId, Long tenantId, String username) {
        return createToken(userId, tenantId, username, 0);
    }

    /**
     * The {@code tv} claim pins the token to the user's token_version at issuance;
     * JwtAuthFilter re-reads the user row per request and rejects on mismatch, so
     * bumping the column (logout-all, password change, reuse kill, admin action)
     * revokes every outstanding access token on its next request.
     */
    public String createToken(Long userId, Long tenantId, String username, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("username", username)
                .claim("tv", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** Tokens minted before V11 carry no tv claim; a missing claim reads as 0. */
    public int getTokenVersion(Claims claims) {
        Object tv = claims.get("tv");
        return tv instanceof Number number ? number.intValue() : 0;
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
