package com.eventsphere.modules.auth;

import com.eventsphere.common.error.BusinessException;
import com.eventsphere.common.error.DelegateNotFoundException;
import com.eventsphere.common.error.OtpCooldownException;
import com.eventsphere.common.security.EventSphereUserPrincipal;
import com.eventsphere.common.security.JwtService;
import com.eventsphere.modules.attendee.AttendeeEntity;
import com.eventsphere.modules.attendee.AttendeeRepository;
import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.attendee.model.UserRole;
import com.eventsphere.modules.auth.dto.AccessResponse;
import com.eventsphere.modules.auth.dto.UserSummaryDto;
import com.eventsphere.modules.auth.email.EmailService;
import com.eventsphere.modules.exhibitor.ExhibitorEntity;
import com.eventsphere.modules.exhibitor.ExhibitorRepository;
import com.eventsphere.modules.exhibitor.ExhibitorStaffEntity;
import com.eventsphere.modules.exhibitor.ExhibitorStaffRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);
    private static final int OTP_TTL_MINUTES = 5;
    private static final int COOLDOWN_SECONDS = 45;
    private static final int MAX_ATTEMPTS = 5;

    public static class OtpEntry {
        private final String code;
        private final AtomicInteger attempts = new AtomicInteger(0);
        private final Instant createdAt = Instant.now();

        public OtpEntry(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public AtomicInteger getAttempts() {
            return attempts;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    private final AttendeeRepository attendeeRepository;
    private final ExhibitorStaffRepository exhibitorStaffRepository;
    private final ExhibitorRepository exhibitorRepository;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Cache<String, Instant> cooldownCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(COOLDOWN_SECONDS))
            .maximumSize(100_000)
            .build();

    private final Cache<String, OtpEntry> otpCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(OTP_TTL_MINUTES))
            .maximumSize(100_000)
            .build();

    public EmailOtpService(
            AttendeeRepository attendeeRepository,
            ExhibitorStaffRepository exhibitorStaffRepository,
            ExhibitorRepository exhibitorRepository,
            JwtService jwtService,
            EmailService emailService
    ) {
        this.attendeeRepository = attendeeRepository;
        this.exhibitorStaffRepository = exhibitorStaffRepository;
        this.exhibitorRepository = exhibitorRepository;
        this.jwtService = jwtService;
        this.emailService = emailService;
    }

    public void requestOtp(String rawEmail) {
        String normalizedEmail = normalizeEmail(rawEmail);

        // 1. Cooldown check (reject if requested within 45 seconds)
        if (cooldownCache.getIfPresent(normalizedEmail) != null) {
            throw new OtpCooldownException("An OTP was recently requested. Please wait 45 seconds before requesting another.");
        }

        // 2. Generate 6-digit numeric OTP
        String otp = generateOtpCode();

        // 3. Store in TTL caches
        otpCache.put(normalizedEmail, new OtpEntry(otp));
        cooldownCache.put(normalizedEmail, Instant.now());

        // 4. Asynchronous non-blocking email dispatch
        CompletableFuture.runAsync(() -> {
            try {
                emailService.sendOtp(normalizedEmail, otp);
            } catch (Throwable t) {
                log.error("Async email delivery failure for [{}]: {}", normalizedEmail, t.getMessage());
            }
        });

        log.info("OTP requested for email={}", maskEmail(normalizedEmail));
    }

    public AccessResponse verifyOtp(String rawEmail, String otp) {
        String normalizedEmail = normalizeEmail(rawEmail);
        String trimmedOtp = otp != null ? otp.trim() : "";

        OtpEntry entry = otpCache.getIfPresent(normalizedEmail);
        if (entry == null) {
            throw new BusinessException("OTP is invalid or has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        int currentAttempt = entry.getAttempts().incrementAndGet();

        // Brute-force protection: Max 5 failed attempts
        if (currentAttempt > MAX_ATTEMPTS) {
            otpCache.invalidate(normalizedEmail);
            throw new BusinessException("Maximum verification attempts exceeded. OTP invalidated.", HttpStatus.TOO_MANY_REQUESTS);
        }

        // Constant-time comparison
        boolean match = MessageDigest.isEqual(
                entry.getCode().getBytes(),
                trimmedOtp.getBytes()
        );

        if (!match) {
            if (currentAttempt >= MAX_ATTEMPTS) {
                otpCache.invalidate(normalizedEmail);
                throw new BusinessException("Maximum verification attempts exceeded. OTP invalidated.", HttpStatus.TOO_MANY_REQUESTS);
            }
            int remaining = MAX_ATTEMPTS - currentAttempt;
            throw new BusinessException("Invalid OTP code. " + remaining + " attempts remaining.", HttpStatus.BAD_REQUEST);
        }

        // Success: atomically invalidate OTP key from memory
        otpCache.invalidate(normalizedEmail);

        // Check if delegate already exists
        Optional<AttendeeEntity> attendeeOpt = attendeeRepository.findFirstByEmailIgnoreCase(normalizedEmail);
        if (attendeeOpt.isEmpty()) {
            log.info("OTP verified for unregistered email={}. Requiring profile creation.", maskEmail(normalizedEmail));
            AccessResponse response = new AccessResponse();
            response.setNewUser(true);
            UserSummaryDto userSummary = new UserSummaryDto();
            userSummary.setEmail(normalizedEmail);
            userSummary.setEventId("MPSS26");
            response.setUser(userSummary);
            return response;
        }

        return buildAccessResponse(attendeeOpt.get());
    }

    public AccessResponse registerDelegate(String eventId, String rawEmail, String rawName) {
        String normalizedEmail = normalizeEmail(rawEmail);
        String trimmedName = rawName != null ? rawName.trim() : "Delegate";
        String normalizedEventId = (eventId != null && !eventId.isBlank()) ? eventId.trim().toUpperCase(Locale.ROOT) : "MPSS26";

        AttendeeEntity attendee = attendeeRepository.findFirstByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> {
                    AttendeeEntity newAttendee = new AttendeeEntity();
                    newAttendee.setId("att-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                    newAttendee.setEventId(normalizedEventId);
                    newAttendee.setRegistrationId("#REG-" + normalizedEventId + "-" + ThreadLocalRandom.current().nextInt(1000, 9999));
                    newAttendee.setName(trimmedName);
                    newAttendee.setEmail(normalizedEmail);
                    newAttendee.setCategory(AttendeeCategory.DELEGATE);
                    newAttendee.setAuthRole(UserRole.ROLE_DELEGATE);
                    newAttendee.setTicketType("Delegate");
                    newAttendee.setDesignation("Delegate");
                    newAttendee.setCompany("Summit Attendee");
                    newAttendee.setCheckedIn(true);
                    newAttendee.setCheckedInAt(Instant.now());
                    newAttendee.setRelevanceScore(100);
                    AttendeeEntity saved = attendeeRepository.save(newAttendee);
                    AttendeeEntity result = (saved != null) ? saved : newAttendee;
                    log.info("Created new delegate attendeeId={} email={} name={}", result.getId(), normalizedEmail, trimmedName);
                    return result;
                });

        return buildAccessResponse(attendee);
    }

    public AccessResponse buildAccessResponse(AttendeeEntity attendee) {
        String exhibitorId = null;
        String boothNumber = null;

        if (attendee.getAuthRole() == UserRole.ROLE_EXHIBITOR) {
            Optional<ExhibitorStaffEntity> staffOpt = exhibitorStaffRepository.findFirstByIdAttendeeId(attendee.getId());
            if (staffOpt.isPresent()) {
                exhibitorId = staffOpt.get().getId().getExhibitorId();
                Optional<ExhibitorEntity> exhibitorOpt = exhibitorRepository.findById(exhibitorId);
                if (exhibitorOpt.isPresent()) {
                    boothNumber = exhibitorOpt.get().getBooth();
                }
            }
        }

        EventSphereUserPrincipal principal = new EventSphereUserPrincipal(
                attendee.getId(),
                attendee.getEventId(),
                attendee.getName(),
                attendee.getEmail(),
                attendee.getAuthRole(),
                attendee.getCategory(),
                exhibitorId
        );

        String token = jwtService.generateToken(principal);

        UserSummaryDto userDto = new UserSummaryDto(
                attendee.getId(),
                attendee.getName(),
                attendee.getCategory().getValue(),
                attendee.getAuthRole().name(),
                exhibitorId,
                attendee.getEventId(),
                attendee.getCompany(),
                attendee.getDesignation(),
                attendee.getEmail(),
                attendee.getMobile(),
                attendee.getAvatarUrl(),
                attendee.getTicketType(),
                attendee.isCheckedIn(),
                boothNumber
        );

        return new AccessResponse(token, "Bearer", jwtService.getExpirationSeconds(), userDto);
    }

    public void clearCooldown(String rawEmail) {
        cooldownCache.invalidate(normalizeEmail(rawEmail));
    }

    public void clearOtp(String rawEmail) {
        otpCache.invalidate(normalizeEmail(rawEmail));
    }

    public void seedOtpForTesting(String rawEmail, String otp) {
        otpCache.put(normalizeEmail(rawEmail), new OtpEntry(otp));
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateOtpCode() {
        int code = 100_000 + secureRandom.nextInt(900_000);
        return String.valueOf(code);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***" + email.substring(Math.max(0, atIndex));
        return email.substring(0, 1) + "***" + email.substring(atIndex - 1);
    }
}
