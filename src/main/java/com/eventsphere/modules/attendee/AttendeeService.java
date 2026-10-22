package com.eventsphere.modules.attendee;

import com.eventsphere.common.error.BusinessException;
import com.eventsphere.common.error.ResourceNotFoundException;
import com.eventsphere.modules.attendee.dto.*;
import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.attendee.model.PrivacyPreset;
import com.eventsphere.modules.attendee.model.UserRole;
import com.eventsphere.modules.connection.ConnectionService;
import com.eventsphere.modules.exhibitor.ExhibitorRepository;
import com.eventsphere.modules.exhibitor.SavedExhibitorEntity;
import com.eventsphere.modules.exhibitor.SavedExhibitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class AttendeeService {

    private static final Logger log = LoggerFactory.getLogger(AttendeeService.class);

    private final AttendeeRepository attendeeRepository;
    private final PrivacyConsentRepository privacyConsentRepository;
    private final SavedPersonRepository savedPersonRepository;
    private final SavedExhibitorRepository savedExhibitorRepository;
    private final ExhibitorRepository exhibitorRepository;
    private final ConnectionService connectionService;

    public AttendeeService(
            AttendeeRepository attendeeRepository,
            PrivacyConsentRepository privacyConsentRepository,
            SavedPersonRepository savedPersonRepository,
            SavedExhibitorRepository savedExhibitorRepository,
            ExhibitorRepository exhibitorRepository,
            ConnectionService connectionService
    ) {
        this.attendeeRepository = attendeeRepository;
        this.privacyConsentRepository = privacyConsentRepository;
        this.savedPersonRepository = savedPersonRepository;
        this.savedExhibitorRepository = savedExhibitorRepository;
        this.exhibitorRepository = exhibitorRepository;
        this.connectionService = connectionService;
    }

    @Transactional(readOnly = true)
    public List<AttendeeProfileDto> getDirectory(
            String eventId,
            String viewerId,
            String query,
            String category,
            boolean savedOnly,
            int page,
            int size
    ) {
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = (size > 0 && size <= 100) ? size : 30;
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        String searchQuery = (query != null && !query.isBlank()) ? query.trim() : null;

        List<AttendeeEntity> attendees;
        if (category != null && !category.isBlank()) {
            AttendeeCategory cat = AttendeeCategory.fromValue(category);
            attendees = attendeeRepository.searchCandidatesByCategories(eventId, viewerId, searchQuery, List.of(cat), pageable);
        } else {
            // Include all attendees except EXHIBITOR_STAFF or show all
            attendees = attendeeRepository.searchCandidates(eventId, viewerId, searchQuery, AttendeeCategory.EXHIBITOR_STAFF, pageable);
        }

        Set<String> savedPersonIds = savedPersonRepository.findAllByAttendeeId(viewerId).stream()
                .map(SavedPersonEntity::getTargetPersonId)
                .collect(Collectors.toSet());

        Map<String, ConnectionService.ConnectionStatusInfo> batchConnInfo =
                connectionService.getBatchConnectionStatusInfo(eventId, viewerId);

        ConnectionService.ConnectionStatusInfo defaultNoneConn =
                new ConnectionService.ConnectionStatusInfo("none", null);

        return attendees.stream()
                .filter(a -> !a.getId().equals(viewerId)) // Filter out self
                .filter(a -> !savedOnly || savedPersonIds.contains(a.getId()))
                .map(a -> {
                    ConnectionService.ConnectionStatusInfo connInfo =
                            batchConnInfo.getOrDefault(a.getId(), defaultNoneConn);
                    boolean isSaved = savedPersonIds.contains(a.getId());
                    return toProfileDto(a, viewerId, connInfo, isSaved);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AttendeeProfileDto getAttendeeProfile(String eventId, String viewerId, String targetId) {
        AttendeeEntity target = attendeeRepository.findByIdAndEventId(targetId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendee", targetId));

        ConnectionService.ConnectionStatusInfo connInfo =
                connectionService.getConnectionStatusInfo(eventId, viewerId, targetId);
        boolean isSaved = savedPersonRepository.existsByAttendeeIdAndTargetPersonId(viewerId, targetId);

        return toProfileDto(target, viewerId, connInfo, isSaved);
    }

    public AttendeeProfileDto updateMyProfile(String eventId, String viewerId, UpdateProfileRequest request) {
        AttendeeEntity attendee = attendeeRepository.findByIdAndEventId(viewerId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendee", viewerId));

        if (request.getName() != null && !request.getName().isBlank()) attendee.setName(request.getName().trim());
        if (request.getDesignation() != null) attendee.setDesignation(request.getDesignation().trim());
        if (request.getCompany() != null) attendee.setCompany(request.getCompany().trim());
        if (request.getBio() != null) attendee.setBio(request.getBio().trim());
        if (request.getAvatar() != null) attendee.setAvatarUrl(request.getAvatar().trim());
        if (request.getLinkedin() != null) attendee.setLinkedinUrl(request.getLinkedin().trim());
        if (request.getTwitter() != null) attendee.setTwitterUrl(request.getTwitter().trim());

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            attendee.setEmail(request.getEmail().trim().toLowerCase());
        }
        if (request.getMobile() != null && !request.getMobile().isBlank()) {
            attendee.setMobile(request.getMobile().trim());
            String digits = request.getMobile().replaceAll("\\D", "");
            if (digits.startsWith("91") && digits.length() > 10) digits = digits.substring(2);
            if (digits.startsWith("0")) digits = digits.substring(1);
            attendee.setNormalizedMobile(digits);
        }

        if (request.getSkills() != null) {
            attendee.setSkillsList(request.getSkills());
        }
        if (request.getInterests() != null) {
            attendee.setInterestsList(request.getInterests());
        }
        if (request.getTicketType() != null && !request.getTicketType().isBlank()) {
            attendee.setTicketType(request.getTicketType().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            attendee.setCategory(AttendeeCategory.fromValue(request.getCategory()));
        }

        AttendeeEntity saved = attendeeRepository.save(attendee);
        log.info("Updated profile for attendee {}", viewerId);

        ConnectionService.ConnectionStatusInfo connInfo = new ConnectionService.ConnectionStatusInfo("none", null);
        return toProfileDto(saved, viewerId, connInfo, false);
    }

    public AttendeeProfileDto updateAttendee(String eventId, String attendeeId, UpdateProfileRequest request) {
        AttendeeEntity attendee = attendeeRepository.findByIdAndEventId(attendeeId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendee", attendeeId));

        if (request.getName() != null && !request.getName().isBlank()) attendee.setName(request.getName().trim());
        if (request.getDesignation() != null) attendee.setDesignation(request.getDesignation().trim());
        if (request.getCompany() != null) attendee.setCompany(request.getCompany().trim());
        if (request.getBio() != null) attendee.setBio(request.getBio().trim());
        if (request.getAvatar() != null) attendee.setAvatarUrl(request.getAvatar().trim());
        if (request.getLinkedin() != null) attendee.setLinkedinUrl(request.getLinkedin().trim());
        if (request.getTwitter() != null) attendee.setTwitterUrl(request.getTwitter().trim());

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            attendee.setEmail(request.getEmail().trim().toLowerCase());
        }
        if (request.getMobile() != null && !request.getMobile().isBlank()) {
            attendee.setMobile(request.getMobile().trim());
            String digits = request.getMobile().replaceAll("\\D", "");
            if (digits.startsWith("91") && digits.length() > 10) digits = digits.substring(2);
            if (digits.startsWith("0")) digits = digits.substring(1);
            attendee.setNormalizedMobile(digits);
        }
        if (request.getSkills() != null) {
            attendee.setSkillsList(request.getSkills());
        }
        if (request.getInterests() != null) {
            attendee.setInterestsList(request.getInterests());
        }
        if (request.getTicketType() != null && !request.getTicketType().isBlank()) {
            attendee.setTicketType(request.getTicketType().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            attendee.setCategory(AttendeeCategory.fromValue(request.getCategory()));
        }

        AttendeeEntity saved = attendeeRepository.save(attendee);
        log.info("Updated attendee record for {}", attendeeId);

        ConnectionService.ConnectionStatusInfo connInfo = new ConnectionService.ConnectionStatusInfo("none", null);
        return toProfileDto(saved, attendeeId, connInfo, false);
    }

    public PrivacyConsentDto updateMyPrivacy(String viewerId, UpdatePrivacyRequest request) {
        AttendeeEntity attendee = attendeeRepository.findById(viewerId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendee", viewerId));

        PrivacyConsentEntity privacy = attendee.getPrivacyConsent();
        if (privacy == null) {
            privacy = new PrivacyConsentEntity();
            privacy.setAttendee(attendee);
            privacy.setAttendeeId(viewerId);
        }

        if (request.getPreset() != null) privacy.setPreset(request.getPreset());
        if (request.getShareEmail() != null) privacy.setShareEmail(request.getShareEmail());
        if (request.getSharePhone() != null) privacy.setSharePhone(request.getSharePhone());
        if (request.getShareLinkedIn() != null) privacy.setShareLinkedIn(request.getShareLinkedIn());
        if (request.getShareTwitter() != null) privacy.setShareTwitter(request.getShareTwitter());

        PrivacyConsentEntity saved = privacyConsentRepository.save(privacy);
        log.info("Updated privacy consent for attendee {}: preset={}", viewerId, saved.getPreset());

        return new PrivacyConsentDto(
                saved.getPreset(),
                saved.isShareEmail(),
                saved.isSharePhone(),
                saved.isShareLinkedIn(),
                saved.isShareTwitter()
        );
    }

    @Transactional
    public void savePerson(String viewerId, String targetPersonId) {
        if (viewerId.equals(targetPersonId)) {
            throw new BusinessException("Cannot save your own profile");
        }

        if (!attendeeRepository.existsById(targetPersonId)) {
            if (targetPersonId.startsWith("speaker-") || targetPersonId.startsWith("spk-")) {
                String cleanName = targetPersonId.replaceAll("^(speaker-|spk-panel-[^-]+-|spk-)", "").replace("-", " ").trim();
                if (cleanName.isBlank()) {
                    cleanName = "Speaker " + targetPersonId;
                } else {
                    cleanName = Arrays.stream(cleanName.split("\\s+"))
                            .map(w -> w.length() > 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : "")
                            .collect(Collectors.joining(" "));
                }
                AttendeeEntity speakerPlaceholder = new AttendeeEntity();
                speakerPlaceholder.setId(targetPersonId);
                speakerPlaceholder.setEventId("MPSS26");
                speakerPlaceholder.setRegistrationId("#SPK-" + Math.abs(targetPersonId.hashCode() % 10000));
                speakerPlaceholder.setName(cleanName);
                speakerPlaceholder.setEmail(targetPersonId + "@mpss2026.in");
                speakerPlaceholder.setMobile("Not listed");
                speakerPlaceholder.setDesignation("Featured Speaker");
                speakerPlaceholder.setCompany("MP Semiconductor Summit 2026");
                speakerPlaceholder.setCategory(AttendeeCategory.SPEAKER);
                speakerPlaceholder.setAuthRole(UserRole.ROLE_DELEGATE);
                speakerPlaceholder.setTicketType("Speaker");
                speakerPlaceholder.setBio(cleanName + " is a featured speaker at the Madhya Pradesh Semiconductor Summit 2026.");
                speakerPlaceholder.setAvatarUrl("");
                speakerPlaceholder.setLinkedinUrl("");
                speakerPlaceholder.setTwitterUrl("");
                speakerPlaceholder.setRelevanceScore(95);
                speakerPlaceholder.setCheckedIn(true);
                attendeeRepository.save(speakerPlaceholder);

                PrivacyConsentEntity consent = new PrivacyConsentEntity(
                        targetPersonId,
                        PrivacyPreset.CONNECTIONS_ONLY,
                        false,
                        false,
                        true,
                        false
                );
                consent.setAttendee(speakerPlaceholder);
                privacyConsentRepository.save(consent);
                log.info("Auto-provisioned speaker attendee record for {}", targetPersonId);
            } else {
                throw new ResourceNotFoundException("Target attendee not found: " + targetPersonId);
            }
        }

        if (!savedPersonRepository.existsByAttendeeIdAndTargetPersonId(viewerId, targetPersonId)) {
            savedPersonRepository.save(new SavedPersonEntity(viewerId, targetPersonId));
            log.info("Attendee {} saved person {}", viewerId, targetPersonId);
        }
    }

    public void unsavePerson(String viewerId, String targetPersonId) {
        savedPersonRepository.deleteByAttendeeIdAndTargetPersonId(viewerId, targetPersonId);
        log.info("Attendee {} unsaved person {}", viewerId, targetPersonId);
    }

    @Transactional(readOnly = true)
    public List<AttendeeProfileDto> getSavedPeople(String eventId, String viewerId) {
        List<SavedPersonEntity> saved = savedPersonRepository.findAllByAttendeeId(viewerId);
        Set<String> targetIds = saved.stream().map(SavedPersonEntity::getTargetPersonId).collect(Collectors.toSet());

        List<AttendeeEntity> targets = attendeeRepository.findAllById(targetIds);

        return targets.stream().map(target -> {
            ConnectionService.ConnectionStatusInfo connInfo =
                    connectionService.getConnectionStatusInfo(eventId, viewerId, target.getId());
            return toProfileDto(target, viewerId, connInfo, true);
        }).collect(Collectors.toList());
    }

    public void saveExhibitor(String viewerId, String targetExhibitorId) {
        if (!exhibitorRepository.existsById(targetExhibitorId)) {
            throw new ResourceNotFoundException("Exhibitor", targetExhibitorId);
        }
        if (!savedExhibitorRepository.existsByAttendeeIdAndTargetExhibitorId(viewerId, targetExhibitorId)) {
            savedExhibitorRepository.save(new SavedExhibitorEntity(viewerId, targetExhibitorId));
            log.info("Attendee {} saved exhibitor {}", viewerId, targetExhibitorId);
        }
    }

    public void unsaveExhibitor(String viewerId, String targetExhibitorId) {
        if (!exhibitorRepository.existsById(targetExhibitorId)) {
            throw new ResourceNotFoundException("Exhibitor", targetExhibitorId);
        }
        savedExhibitorRepository.deleteByAttendeeIdAndTargetExhibitorId(viewerId, targetExhibitorId);
        log.info("Attendee {} unsaved exhibitor {}", viewerId, targetExhibitorId);
    }

    @Transactional(readOnly = true)
    public List<String> getSavedExhibitorIds(String viewerId) {
        return savedExhibitorRepository.findAllByAttendeeId(viewerId).stream()
                .map(SavedExhibitorEntity::getTargetExhibitorId)
                .collect(Collectors.toList());
    }

    // Privacy Masking Enforcement
    private AttendeeProfileDto toProfileDto(
            AttendeeEntity target,
            String viewerId,
            ConnectionService.ConnectionStatusInfo connInfo,
            boolean isSaved
    ) {
        boolean isSelf = target.getId().equals(viewerId);
        boolean isConnected = "connected".equalsIgnoreCase(connInfo.getStatus());
        boolean isSpeaker = target.getCategory() == AttendeeCategory.SPEAKER;

        PrivacyConsentEntity privacy = target.getPrivacyConsent();
        PrivacyPreset preset = (privacy != null) ? privacy.getPreset() : (isSpeaker ? PrivacyPreset.OPEN : PrivacyPreset.CONNECTIONS_ONLY);

        boolean canShareEmail;
        boolean canSharePhone;
        boolean canShareLinkedIn;
        boolean canShareTwitter;

        if (isSelf) {
            canShareEmail = true;
            canSharePhone = true;
            canShareLinkedIn = true;
            canShareTwitter = true;
        } else if (isSpeaker) {
            canShareEmail = (privacy != null) && privacy.isShareEmail();
            canSharePhone = (privacy != null) && privacy.isSharePhone();
            canShareLinkedIn = target.getLinkedinUrl() != null;
            canShareTwitter = target.getTwitterUrl() != null;
        } else if (preset == PrivacyPreset.CONNECTIONS_ONLY) {
            if (isConnected) {
                canShareEmail = (privacy == null) || privacy.isShareEmail();
                canSharePhone = (privacy == null) || privacy.isSharePhone();
                canShareLinkedIn = (privacy == null) || privacy.isShareLinkedIn();
                canShareTwitter = (privacy != null) && privacy.isShareTwitter();
            } else {
                canShareEmail = false;
                canSharePhone = false;
                canShareLinkedIn = false;
                canShareTwitter = false;
            }
        } else { // OPEN
            canShareEmail = (privacy == null) || privacy.isShareEmail();
            canSharePhone = (privacy == null) || privacy.isSharePhone();
            canShareLinkedIn = (privacy == null) || privacy.isShareLinkedIn();
            canShareTwitter = (privacy != null) && privacy.isShareTwitter();
        }

        String email = canShareEmail ? target.getEmail() : "contact@eventsphere.internal";
        String mobile = canSharePhone ? target.getMobile() : "+91 98765 00000";
        String linkedin = canShareLinkedIn ? target.getLinkedinUrl() : null;
        String twitter = canShareTwitter ? target.getTwitterUrl() : null;

        PrivacyConsentDto privacyDto = null;
        if (isSelf && privacy != null) {
            privacyDto = new PrivacyConsentDto(
                    privacy.getPreset(),
                    privacy.isShareEmail(),
                    privacy.isSharePhone(),
                    privacy.isShareLinkedIn(),
                    privacy.isShareTwitter()
            );
        }

        return new AttendeeProfileDto(
                target.getId(),
                target.getName(),
                target.getAvatarUrl(),
                target.getDesignation(),
                target.getCompany(),
                target.getCategory().name().toLowerCase(),
                target.getTicketType(),
                target.getBio(),
                email,
                mobile,
                linkedin,
                twitter,
                target.getRelevanceScore(),
                target.isCheckedIn(),
                target.getRegistrationId(),
                privacyDto,
                connInfo.getStatus(),
                connInfo.getConnectionId(),
                isSaved,
                target.getSkillsList(),
                target.getInterestsList()
        );
    }
}
