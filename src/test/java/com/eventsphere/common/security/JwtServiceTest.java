package com.eventsphere.common.security;

import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.attendee.model.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;
    private final String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private final long expirationMs = 3600000; // 1 hour

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(secret, expirationMs);
    }

    @Test
    @DisplayName("Should generate valid token with claims and parse back correctly")
    void shouldGenerateAndParseToken() {
        EventSphereUserPrincipal principal = new EventSphereUserPrincipal(
                "att-123",
                "MPSS26",
                "Jane Doe",
                "jane@example.com",
                UserRole.ROLE_EXHIBITOR,
                AttendeeCategory.EXHIBITOR_STAFF,
                "exh-alpha"
        );

        String token = jwtService.generateToken(principal);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();

        EventSphereUserPrincipal parsed = jwtService.parseUserPrincipal(token);
        assertThat(parsed.getAttendeeId()).isEqualTo("att-123");
        assertThat(parsed.getEventId()).isEqualTo("MPSS26");
        assertThat(parsed.getName()).isEqualTo("Jane Doe");
        assertThat(parsed.getEmail()).isEqualTo("jane@example.com");
        assertThat(parsed.getRole()).isEqualTo(UserRole.ROLE_EXHIBITOR);
        assertThat(parsed.getCategory()).isEqualTo(AttendeeCategory.EXHIBITOR_STAFF);
        assertThat(parsed.getExhibitorId()).isEqualTo("exh-alpha");
    }

    @Test
    @DisplayName("Should parse tokens with plural category 'DELEGATES' and role without prefix 'DELEGATE'")
    void shouldParseTokensWithPluralCategoryAndRoleWithoutPrefix() {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("att-render-123")
                .claim("eventId", "MPSS26")
                .claim("name", "Render User")
                .claim("email", "render@example.com")
                .claim("role", "DELEGATE")
                .claim("category", "DELEGATES")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(jwtService.getSigningKey(), Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.isTokenValid(token)).isTrue();
        EventSphereUserPrincipal principal = jwtService.parseUserPrincipal(token);
        assertThat(principal.getRole()).isEqualTo(UserRole.ROLE_DELEGATE);
        assertThat(principal.getCategory()).isEqualTo(AttendeeCategory.DELEGATE);
        assertThat(principal.getAuthorities()).hasSize(1);
        assertThat(principal.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_DELEGATE");
    }

    @Test
    @DisplayName("Should verify tokens signed with fallback prod secret (raw and base64 decoded)")
    void shouldVerifyFallbackSecrets() {
        String prodSecretRaw = "c3VwZXItc2VjcmV0LWtleS1mb3ItZXZlbnRzcGhlcmUtYmFja2VuZC0yNTYtYml0cw==";
        byte[] decodedBytes = Base64.getDecoder().decode(prodSecretRaw);

        Instant now = Instant.now();
        String tokenFromRawKey = Jwts.builder()
                .subject("att-k6-raw")
                .claim("eventId", "MPSS26")
                .claim("name", "K6 Raw")
                .claim("email", "k6raw@example.com")
                .claim("role", "ROLE_DELEGATE")
                .claim("category", "DELEGATE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(prodSecretRaw.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        String tokenFromDecodedKey = Jwts.builder()
                .subject("att-k6-b64")
                .claim("eventId", "MPSS26")
                .claim("name", "K6 Decoded")
                .claim("email", "k6b64@example.com")
                .claim("role", "ROLE_DELEGATE")
                .claim("category", "DELEGATE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(decodedBytes), Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.isTokenValid(tokenFromRawKey)).isTrue();
        EventSphereUserPrincipal principalRaw = jwtService.parseUserPrincipal(tokenFromRawKey);
        assertThat(principalRaw.getEmail()).isEqualTo("k6raw@example.com");

        assertThat(jwtService.isTokenValid(tokenFromDecodedKey)).isTrue();
        EventSphereUserPrincipal principalB64 = jwtService.parseUserPrincipal(tokenFromDecodedKey);
        assertThat(principalB64.getEmail()).isEqualTo("k6b64@example.com");
    }

    @Test
    @DisplayName("Should recognize and parse delegate fallback pseudo-token")
    void shouldParseDelegatePseudoToken() {
        String pseudoToken = "mpss26-delegate-token-harigupta%40getmyevent.in";

        assertThat(jwtService.isTokenValid(pseudoToken)).isTrue();
        EventSphereUserPrincipal principal = jwtService.parseUserPrincipal(pseudoToken);
        assertThat(principal.getEmail()).isEqualTo("harigupta@getmyevent.in");
        assertThat(principal.getRole()).isEqualTo(UserRole.ROLE_DELEGATE);
        assertThat(principal.getCategory()).isEqualTo(AttendeeCategory.DELEGATE);
        assertThat(principal.getEventId()).isEqualTo("MPSS26");
    }

    @Test
    @DisplayName("Should reject tampered token")
    void shouldRejectTamperedToken() {
        EventSphereUserPrincipal principal = new EventSphereUserPrincipal(
                "att-123",
                "MPSS26",
                "Jane Doe",
                "jane@example.com",
                UserRole.ROLE_DELEGATE,
                AttendeeCategory.DELEGATE,
                null
        );

        String token = jwtService.generateToken(principal);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("Should reject expired token")
    void shouldRejectExpiredToken() {
        JwtService expiredService = new JwtService(secret, -1000);

        EventSphereUserPrincipal principal = new EventSphereUserPrincipal(
                "att-123",
                "MPSS26",
                "Jane Doe",
                "jane@example.com",
                UserRole.ROLE_DELEGATE,
                AttendeeCategory.DELEGATE,
                null
        );

        String token = expiredService.generateToken(principal);
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }
}
