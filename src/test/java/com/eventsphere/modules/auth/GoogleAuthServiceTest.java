package com.eventsphere.modules.auth;

import com.eventsphere.common.error.BusinessException;
import com.eventsphere.common.error.DelegateNotFoundException;
import com.eventsphere.common.security.JwtService;
import com.eventsphere.modules.attendee.AttendeeEntity;
import com.eventsphere.modules.attendee.AttendeeRepository;
import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.attendee.model.UserRole;
import com.eventsphere.modules.auth.dto.AccessResponse;
import com.eventsphere.modules.auth.dto.GoogleAuthRequest;
import com.eventsphere.modules.auth.dto.GooglePayload;
import com.eventsphere.modules.auth.email.EmailService;
import com.eventsphere.modules.exhibitor.ExhibitorRepository;
import com.eventsphere.modules.exhibitor.ExhibitorStaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTest {

    @Mock
    private GoogleTokenVerifier googleTokenVerifier;

    @Mock
    private AttendeeRepository attendeeRepository;

    @Mock
    private ExhibitorStaffRepository exhibitorStaffRepository;

    @Mock
    private ExhibitorRepository exhibitorRepository;

    @Mock
    private EmailService emailService;

    private GoogleAuthService googleAuthService;
    private AttendeeEntity attendee;

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970", 259200000L);
        EmailOtpService emailOtpService = new EmailOtpService(
                attendeeRepository,
                exhibitorStaffRepository,
                exhibitorRepository,
                jwtService,
                emailService
        );
        googleAuthService = new GoogleAuthService(googleTokenVerifier, attendeeRepository, emailOtpService);

        attendee = new AttendeeEntity();
        attendee.setId("att-google-1");
        attendee.setEventId("MPSS26");
        attendee.setName("Google Delegate");
        attendee.setEmail("delegate@gmail.com");
        attendee.setAuthRole(UserRole.ROLE_DELEGATE);
        attendee.setCategory(AttendeeCategory.DELEGATE);
    }

    @Test
    @DisplayName("Google Auth: Successfully authenticate when token is valid and email is registered")
    void testGoogleAuthSuccess() {
        when(googleTokenVerifier.verify("valid-token"))
                .thenReturn(new GooglePayload("delegate@gmail.com", true, "Google Delegate", "https://photo", "sub-1"));
        when(attendeeRepository.findFirstByEmailIgnoreCase("delegate@gmail.com"))
                .thenReturn(Optional.of(attendee));

        AccessResponse response = googleAuthService.authenticate(new GoogleAuthRequest("valid-token"));

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getUser().getEmail()).isEqualTo("delegate@gmail.com");
    }

    @Test
    @DisplayName("Google Auth: Returns isNewUser=true when email is not found in database and name is missing")
    void testGoogleAuthUnregisteredReturnsNewUser() {
        when(googleTokenVerifier.verify("unregistered-token"))
                .thenReturn(new GooglePayload("unregistered@gmail.com", true, null, null, "sub-2"));
        when(attendeeRepository.findFirstByEmailIgnoreCase("unregistered@gmail.com"))
                .thenReturn(Optional.empty());

        AccessResponse response = googleAuthService.authenticate(new GoogleAuthRequest("unregistered-token"));

        assertThat(response).isNotNull();
        assertThat(response.isNewUser()).isTrue();
        assertThat(response.getUser().getEmail()).isEqualTo("unregistered@gmail.com");
    }

    @Test
    @DisplayName("Google Auth: Auto-registers when name is present in Google payload")
    void testGoogleAuthUnregisteredWithNameAutoRegisters() {
        when(googleTokenVerifier.verify("unregistered-token-2"))
                .thenReturn(new GooglePayload("new.delegate@gmail.com", true, "New Delegate", null, "sub-3"));
        when(attendeeRepository.findFirstByEmailIgnoreCase("new.delegate@gmail.com"))
                .thenReturn(Optional.empty());

        AccessResponse response = googleAuthService.authenticate(new GoogleAuthRequest("unregistered-token-2"));

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getUser().getEmail()).isEqualTo("new.delegate@gmail.com");
        assertThat(response.getUser().getName()).isEqualTo("New Delegate");
    }

    @Test
    @DisplayName("Google Auth: Propagates UNAUTHORIZED exception on invalid token signature")
    void testGoogleAuthInvalidToken() {
        when(googleTokenVerifier.verify("bad-token"))
                .thenThrow(new BusinessException("Invalid or expired Google ID token", HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> googleAuthService.authenticate(new GoogleAuthRequest("bad-token")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }
}
