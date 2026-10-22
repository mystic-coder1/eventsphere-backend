package com.eventsphere.modules.auth;

import com.eventsphere.common.error.BusinessException;
import com.eventsphere.common.security.RateLimiterService;
import com.eventsphere.modules.attendee.AttendeeEntity;
import com.eventsphere.modules.attendee.AttendeeRepository;
import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.attendee.model.UserRole;
import com.eventsphere.modules.auth.dto.*;
import com.eventsphere.modules.event.EventEntity;
import com.eventsphere.modules.event.EventRepository;
import com.eventsphere.modules.exhibitor.ExhibitorEntity;
import com.eventsphere.modules.exhibitor.ExhibitorRepository;
import com.eventsphere.modules.exhibitor.ExhibitorStaffEntity;
import com.eventsphere.modules.exhibitor.ExhibitorStaffRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AttendeeRepository attendeeRepository;

    @Autowired
    private ExhibitorRepository exhibitorRepository;

    @Autowired
    private ExhibitorStaffRepository exhibitorStaffRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @MockBean
    private GoogleTokenVerifier googleTokenVerifier;

    @Autowired
    private EmailOtpService emailOtpService;

    private static final String EVENT_ID = "MPSS26";

    @BeforeEach
    void setUp() {
        rateLimiterService.reset();
        emailOtpService.clearCooldown("hari@eventsphere.com");
        emailOtpService.clearOtp("hari@eventsphere.com");

        // 1. Seed Event
        EventEntity event = new EventEntity(
                EVENT_ID,
                "MP Semiconductor Summit 2026",
                LocalDate.now(),
                LocalDate.now().plusDays(1),
                "23–24 Oct 2026",
                "Indore",
                "Brilliant Convention Centre",
                "UPCOMING"
        );
        eventRepository.save(event);

        // 2. Seed Delegate
        AttendeeEntity delegate = new AttendeeEntity();
        delegate.setId("att-delegate-1");
        delegate.setEventId(EVENT_ID);
        delegate.setRegistrationId("#REG-DEL-01");
        delegate.setName("Hari Gupta");
        delegate.setEmail("hari@eventsphere.com");
        delegate.setMobile("+91 98765 43210");
        delegate.setNormalizedMobile("9876543210");
        delegate.setCategory(AttendeeCategory.DELEGATE);
        delegate.setAuthRole(UserRole.ROLE_DELEGATE);
        delegate.setCompany("EventSphere");
        delegate.setDesignation("CEO");
        delegate.setTicketType("Full Access");
        delegate.setCheckedIn(true);
        attendeeRepository.save(delegate);

        // 3. Seed Exhibitor and Staff
        ExhibitorEntity exhibitor = new ExhibitorEntity();
        exhibitor.setId("exh-truechip");
        exhibitor.setEventId(EVENT_ID);
        exhibitor.setCompany("Truechip Solutions");
        exhibitor.setBooth("Hall 1 · A-02");
        exhibitor.setCategory("Verification IP");
        exhibitorRepository.save(exhibitor);

        AttendeeEntity exhStaff = new AttendeeEntity();
        exhStaff.setId("att-exh-staff-1");
        exhStaff.setEventId(EVENT_ID);
        exhStaff.setRegistrationId("#REG-EXH-01");
        exhStaff.setName("Truechip Engineer");
        exhStaff.setEmail("staff@truechip.net");
        exhStaff.setMobile("+91 98765 43211");
        exhStaff.setNormalizedMobile("9876543211");
        exhStaff.setCategory(AttendeeCategory.EXHIBITOR_STAFF);
        exhStaff.setAuthRole(UserRole.ROLE_EXHIBITOR);
        exhStaff.setCompany("Truechip Solutions");
        exhStaff.setDesignation("Lead Engineer");
        exhStaff.setTicketType("Exhibitor Pass");
        attendeeRepository.save(exhStaff);

        ExhibitorStaffEntity staffMapping = new ExhibitorStaffEntity(EVENT_ID, exhibitor.getId(), exhStaff.getId(), true);
        exhibitorStaffRepository.save(staffMapping);

        // 4. Seed Organizer Staff
        AttendeeEntity orgStaff = new AttendeeEntity();
        orgStaff.setId("att-org-staff-1");
        orgStaff.setEventId(EVENT_ID);
        orgStaff.setRegistrationId("#REG-ORG-01");
        orgStaff.setName("Summit Operations");
        orgStaff.setEmail("ops@mpss26.org");
        orgStaff.setMobile("+91 98765 43212");
        orgStaff.setNormalizedMobile("9876543212");
        orgStaff.setCategory(AttendeeCategory.ORGANIZER_STAFF);
        orgStaff.setAuthRole(UserRole.ROLE_ORGANIZER);
        orgStaff.setCompany("MPSEDC Operations");
        orgStaff.setDesignation("Operations Lead");
        orgStaff.setTicketType("Organizer Pass");
        attendeeRepository.save(orgStaff);
    }

    @Test
    @DisplayName("Delegate login succeeds and returns valid JWT and UserSummary")
    void testDelegateLoginSuccess() throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, "+91 98765 43210");

        mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresIn", greaterThan(0)))
                .andExpect(jsonPath("$.user.id", is("att-delegate-1")))
                .andExpect(jsonPath("$.user.name", is("Hari Gupta")))
                .andExpect(jsonPath("$.user.role", is("ROLE_DELEGATE")))
                .andExpect(jsonPath("$.user.category", is("delegate")))
                .andExpect(jsonPath("$.user.exhibitorId").doesNotExist());
    }

    @Test
    @DisplayName("Exhibitor staff login resolves exhibitor scope and booth number")
    void testExhibitorLoginSuccess() throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, "9876543211");

        mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id", is("att-exh-staff-1")))
                .andExpect(jsonPath("$.user.role", is("ROLE_EXHIBITOR")))
                .andExpect(jsonPath("$.user.category", is("exhibitor_staff")))
                .andExpect(jsonPath("$.user.exhibitorId", is("exh-truechip")))
                .andExpect(jsonPath("$.user.boothNumber", is("Hall 1 · A-02")));
    }

    @Test
    @DisplayName("Organizer login succeeds with standard 10-digit mobile number")
    void testOrganizerLoginSuccess() throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, "9876543212");

        mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id", is("att-org-staff-1")))
                .andExpect(jsonPath("$.user.role", is("ROLE_ORGANIZER")))
                .andExpect(jsonPath("$.user.category", is("organizer_staff")));
    }

    @Test
    @DisplayName("Unregistered mobile returns 404 with standard ErrorResponse")
    void testUnregisteredMobileReturns404() throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, "9999999999");

        mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")))
                .andExpect(jsonPath("$.message", containsString("No registration found")));
    }

    @Test
    @DisplayName("Blank fields return 400 with validation errors")
    void testBlankFieldsReturn400() throws Exception {
        AccessRequest request = new AccessRequest("", "");

        mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors.eventId", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.mobile", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me returns current user profile when valid token supplied")
    void testGetMeWithValidToken() throws Exception {
        String token = obtainToken("9876543210");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("att-delegate-1")))
                .andExpect(jsonPath("$.name", is("Hari Gupta")))
                .andExpect(jsonPath("$.role", is("ROLE_DELEGATE")));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me returns 401 Unauthorized when token missing")
    void testGetMeWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", containsString("Authentication required")));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me returns 401 Unauthorized when token invalid")
    void testGetMeWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer invalid-garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("Role-based authorization enforces endpoint permissions")
    void testRoleBasedPermissions() throws Exception {
        String delegateToken = obtainToken("9876543210");
        String exhibitorToken = obtainToken("9876543211");
        String organizerToken = obtainToken("9876543212");

        // 1. /api/v1/exhibitor/**: Delegate -> 403 Forbidden
        mockMvc.perform(get("/api/v1/exhibitor/protected-test")
                        .header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));

        // 2. /api/v1/exhibitor/**: Exhibitor -> 200 OK
        mockMvc.perform(get("/api/v1/exhibitor/protected-test")
                        .header("Authorization", "Bearer " + exhibitorToken))
                .andExpect(status().isOk());

        // 3. /api/v1/exhibitor/**: Organizer -> 200 OK
        mockMvc.perform(get("/api/v1/exhibitor/protected-test")
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isOk());

        // 4. /api/v1/organizer/**: Exhibitor -> 403 Forbidden
        mockMvc.perform(get("/api/v1/organizer/protected-test")
                        .header("Authorization", "Bearer " + exhibitorToken))
                .andExpect(status().isForbidden());

        // 5. /api/v1/organizer/**: Organizer -> 200 OK
        mockMvc.perform(get("/api/v1/organizer/protected-test")
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Exceeding rate limit triggers 429 Too Many Requests")
    void testRateLimiterReturns429() throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, "9876543210");

        // Exhaust the 10 mobile per-minute limit
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/access")
                            .header("X-Forwarded-For", "192.168.1." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // 11th request must be rejected with 429
        mockMvc.perform(post("/api/v1/auth/access")
                        .header("X-Forwarded-For", "192.168.1.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.message", containsString("Too many access attempts")));
    }

    @Test
    @DisplayName("Google OAuth: Successfully authenticate registered delegate via Google ID token")
    void testGoogleAuthSuccess() throws Exception {
        when(googleTokenVerifier.verify("valid-google-id-token"))
                .thenReturn(new GooglePayload("hari@eventsphere.com", true, "Hari Gupta", "https://avatar.png", "sub-12345"));

        GoogleAuthRequest request = new GoogleAuthRequest("valid-google-id-token");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.id", is("att-delegate-1")))
                .andExpect(jsonPath("$.user.email", is("hari@eventsphere.com")))
                .andExpect(jsonPath("$.user.name", is("Hari Gupta")))
                .andExpect(jsonPath("$.user.role", is("ROLE_DELEGATE")));
    }

    @Test
    @DisplayName("Google OAuth: Unregistered email without name returns 200 with isNewUser=true")
    void testGoogleAuthUnregisteredEmailReturnsNewUser() throws Exception {
        when(googleTokenVerifier.verify("unregistered-token"))
                .thenReturn(new GooglePayload("unknown.delegate@gmail.com", true, null, null, "sub-999"));

        GoogleAuthRequest request = new GoogleAuthRequest("unregistered-token");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newUser", is(true)))
                .andExpect(jsonPath("$.user.email", is("unknown.delegate@gmail.com")));
    }

    @Test
    @DisplayName("Google OAuth: Unregistered email with name auto-registers and returns token")
    void testGoogleAuthUnregisteredEmailWithAutoRegister() throws Exception {
        when(googleTokenVerifier.verify("autoreg-token"))
                .thenReturn(new GooglePayload("new.delegate@gmail.com", true, "New Google Delegate", null, "sub-888"));

        GoogleAuthRequest request = new GoogleAuthRequest("autoreg-token");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email", is("new.delegate@gmail.com")))
                .andExpect(jsonPath("$.user.name", is("New Google Delegate")));
    }

    @Test
    @DisplayName("Google OAuth: Invalid token signature returns 401 Unauthorized")
    void testGoogleAuthInvalidTokenSignatureReturns401() throws Exception {
        when(googleTokenVerifier.verify("invalid-token"))
                .thenThrow(new BusinessException("Invalid or expired Google ID token", HttpStatus.UNAUTHORIZED));

        GoogleAuthRequest request = new GoogleAuthRequest("invalid-token");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("Invalid or expired Google ID token")));
    }

    @Test
    @DisplayName("Email OTP: Successfully request OTP for registered delegate")
    void testEmailOtpRequestSuccess() throws Exception {
        RequestOtpRequest request = new RequestOtpRequest("hari@eventsphere.com");

        mockMvc.perform(post("/api/auth/email/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("OTP sent successfully")));
    }

    @Test
    @DisplayName("Email OTP: Second OTP request within 45s cooldown returns 429 Too Many Requests")
    void testEmailOtpCooldownReturns429() throws Exception {
        RequestOtpRequest request = new RequestOtpRequest("hari@eventsphere.com");

        // First request: OK
        mockMvc.perform(post("/api/auth/email/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Second request immediately: 429 RATE_LIMITED
        mockMvc.perform(post("/api/auth/email/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("RATE_LIMITED")))
                .andExpect(jsonPath("$.message", containsString("45 seconds")));
    }

    @Test
    @DisplayName("Email OTP: Request OTP for unregistered email returns 200 and verify returns isNewUser=true")
    void testEmailOtpRequestUnregisteredAllowedAndVerifyReturnsNewUser() throws Exception {
        RequestOtpRequest request = new RequestOtpRequest("notfound@domain.com");

        mockMvc.perform(post("/api/auth/email/request-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("OTP sent successfully")));

        emailOtpService.seedOtpForTesting("notfound@domain.com", "654321");
        VerifyOtpRequest verifyReq = new VerifyOtpRequest("notfound@domain.com", "654321");

        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newUser", is(true)))
                .andExpect(jsonPath("$.user.email", is("notfound@domain.com")));
    }

    @Test
    @DisplayName("Register: Successfully creates new delegate attendee and returns JWT")
    void testRegisterDelegateSuccess() throws Exception {
        RegisterDelegateRequest request = new RegisterDelegateRequest("MPSS26", "newdelegate@domain.com", "New Delegate User");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email", is("newdelegate@domain.com")))
                .andExpect(jsonPath("$.user.name", is("New Delegate User")))
                .andExpect(jsonPath("$.user.role", is("ROLE_DELEGATE")));
    }

    @Test
    @DisplayName("Email OTP: Verify valid OTP issues JWT and invalidates OTP key from memory")
    void testEmailOtpVerifySuccessAndAtomicInvalidation() throws Exception {
        emailOtpService.seedOtpForTesting("hari@eventsphere.com", "849201");

        VerifyOtpRequest request = new VerifyOtpRequest("hari@eventsphere.com", "849201");

        // 1. Verify OTP -> 200 OK + JWT
        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.user.id", is("att-delegate-1")))
                .andExpect(jsonPath("$.user.email", is("hari@eventsphere.com")));

        // 2. Replay attempt -> 400 Bad Request (OTP was atomically invalidated on success!)
        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("OTP is invalid or has expired")));
    }

    @Test
    @DisplayName("Email OTP: Verify with wrong code returns 400 with attempts remaining")
    void testEmailOtpVerifyWrongCode() throws Exception {
        emailOtpService.seedOtpForTesting("hari@eventsphere.com", "849201");

        VerifyOtpRequest request = new VerifyOtpRequest("hari@eventsphere.com", "111111");

        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid OTP code. 4 attempts remaining.")));
    }

    @Test
    @DisplayName("Email OTP: Brute-force protection invalidates OTP after 5 failed attempts")
    void testEmailOtpBruteForceProtection() throws Exception {
        emailOtpService.seedOtpForTesting("hari@eventsphere.com", "849201");

        VerifyOtpRequest wrongRequest = new VerifyOtpRequest("hari@eventsphere.com", "000000");

        // 4 failed attempts
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/email/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongRequest)))
                    .andExpect(status().isBadRequest());
        }

        // 5th failed attempt: OTP invalidated with TOO_MANY_REQUESTS
        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message", containsString("Maximum verification attempts exceeded")));

        // 6th attempt (even with CORRECT code): rejected because OTP was invalidated
        VerifyOtpRequest correctRequest = new VerifyOtpRequest("hari@eventsphere.com", "849201");
        mockMvc.perform(post("/api/auth/email/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("OTP is invalid or has expired")));
    }

    private String obtainToken(String mobile) throws Exception {
        AccessRequest request = new AccessRequest(EVENT_ID, mobile);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }
}
