package com.eventsphere.modules.exhibitor;

import com.eventsphere.common.error.BusinessException;
import com.eventsphere.common.error.ResourceNotFoundException;
import com.eventsphere.modules.attendee.AttendeeEntity;
import com.eventsphere.modules.attendee.AttendeeRepository;
import com.eventsphere.modules.attendee.PrivacyConsentEntity;
import com.eventsphere.modules.attendee.model.AttendeeCategory;
import com.eventsphere.modules.exhibitor.dto.*;
import com.eventsphere.modules.exhibitor.model.LeadStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ExhibitorService {

    private static final Logger log = LoggerFactory.getLogger(ExhibitorService.class);

    private final ExhibitorRepository exhibitorRepository;
    private final LeadRepository leadRepository;
    private final ProductRepository productRepository;
    private final AttendeeRepository attendeeRepository;
    private final ObjectMapper objectMapper;

    public ExhibitorService(
            ExhibitorRepository exhibitorRepository,
            LeadRepository leadRepository,
            ProductRepository productRepository,
            AttendeeRepository attendeeRepository,
            ObjectMapper objectMapper
    ) {
        this.exhibitorRepository = exhibitorRepository;
        this.leadRepository = leadRepository;
        this.productRepository = productRepository;
        this.attendeeRepository = attendeeRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ExhibitorStatsDto getStats(String exhibitorId) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);

        long totalLeads = leadRepository.countByExhibitorId(exhibitorId);
        long newLeads = leadRepository.countByExhibitorIdAndStatus(exhibitorId, LeadStatus.NEW);
        long interestedLeads = leadRepository.countByExhibitorIdAndStatus(exhibitorId, LeadStatus.INTERESTED);
        long followUpLeads = leadRepository.countByExhibitorIdAndStatus(exhibitorId, LeadStatus.FOLLOW_UP);

        List<ProductEntity> products = productRepository.findAllByExhibitorIdOrderByCreatedAtAsc(exhibitorId);
        long totalProducts = products.size();
        long totalViews = products.stream().mapToLong(ProductEntity::getViews).sum();
        long totalEnquiries = products.stream().mapToLong(ProductEntity::getEnquiries).sum();

        return new ExhibitorStatsDto(
                exhibitor.getId(),
                exhibitor.getCompany(),
                exhibitor.getBooth(),
                exhibitor.getCategory(),
                exhibitor.getLogoUrl(),
                exhibitor.getTeamSize(),
                exhibitor.getStatus(),
                exhibitor.getBrochureUrl(),
                exhibitor.getBrochureName(),
                totalLeads,
                newLeads,
                interestedLeads,
                followUpLeads,
                totalProducts,
                totalViews,
                totalEnquiries
        );
    }

    @Transactional(readOnly = true)
    public List<AttendeeCandidateDto> searchCandidates(
            String eventId,
            String exhibitorId,
            String query,
            String roleFilter,
            int page,
            int size
    ) {
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = (size > 0 && size <= 100) ? size : 30;
        Pageable pageable = PageRequest.of(sanitizedPage, sanitizedSize);

        String searchQuery = (query != null && !query.isBlank()) ? query.trim() : null;

        List<AttendeeEntity> attendees;
        if ("Delegates".equalsIgnoreCase(roleFilter)) {
            attendees = attendeeRepository.searchCandidatesByCategories(
                    eventId,
                    null,
                    searchQuery,
                    List.of(AttendeeCategory.DELEGATE),
                    pageable
            );
        } else if ("VIP & Speakers".equalsIgnoreCase(roleFilter) || "VIP".equalsIgnoreCase(roleFilter) || "Speakers".equalsIgnoreCase(roleFilter)) {
            attendees = attendeeRepository.searchCandidatesByCategories(
                    eventId,
                    null,
                    searchQuery,
                    List.of(AttendeeCategory.VIP, AttendeeCategory.SPEAKER),
                    pageable
            );
        } else {
            attendees = attendeeRepository.searchCandidates(
                    eventId,
                    null,
                    searchQuery,
                    AttendeeCategory.EXHIBITOR_STAFF,
                    pageable
            );
        }

        List<LeadEntity> existingLeads = leadRepository.findAllByExhibitorIdOrderByCapturedAtDesc(exhibitorId);
        Map<String, LeadEntity> leadByAttendeeId = existingLeads.stream()
                .collect(Collectors.toMap(LeadEntity::getAttendeeId, l -> l, (first, second) -> first));

        return attendees.stream().map(a -> {
            PrivacyConsentEntity privacy = a.getPrivacyConsent();
            boolean canShareEmail = privacy == null || privacy.isShareEmail();
            boolean canSharePhone = privacy == null || privacy.isSharePhone();
            boolean canShareLinkedIn = privacy == null || privacy.isShareLinkedIn();

            String email = canShareEmail ? a.getEmail() : "contact@eventsphere.internal";
            String mobile = canSharePhone ? a.getMobile() : "+91 98765 00000";
            String linkedin = canShareLinkedIn ? (a.getLinkedinUrl() != null ? a.getLinkedinUrl() : "") : "";

            LeadEntity lead = leadByAttendeeId.get(a.getId());
            boolean isLead = lead != null;
            LeadStatus status = isLead ? lead.getStatus() : null;
            String leadId = isLead ? lead.getId() : null;
            List<String> interests = isLead ? readStringList(lead.getInterestsJson()) : List.of();

            return new AttendeeCandidateDto(
                    a.getId(),
                    a.getName(),
                    a.getAvatarUrl(),
                    a.getDesignation(),
                    a.getCompany(),
                    a.getCategory().name().toLowerCase(),
                    email,
                    mobile,
                    linkedin,
                    isLead,
                    status,
                    leadId,
                    interests
            );
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeadDto> getLeads(String exhibitorId, LeadStatus statusFilter) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);
        List<LeadEntity> leads;
        if (statusFilter != null) {
            leads = leadRepository.findAllWithAttendeeByExhibitorIdAndStatus(exhibitorId, statusFilter);
        } else {
            leads = leadRepository.findAllWithAttendeeByExhibitorId(exhibitorId);
        }

        return leads.stream()
                .map(l -> toLeadDto(l, exhibitor.getBooth()))
                .collect(Collectors.toList());
    }

    public LeadDto captureLead(String exhibitorId, CaptureLeadRequest request) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);

        AttendeeEntity attendee = attendeeRepository.findByIdAndEventId(request.getAttendeeId(), exhibitor.getEventId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendee not found for this event: " + request.getAttendeeId()));

        Optional<LeadEntity> existingOpt = leadRepository.findByExhibitorIdAndAttendeeId(exhibitorId, attendee.getId());
        LeadEntity lead;

        if (existingOpt.isPresent()) {
            lead = existingOpt.get();
            log.info("Lead already exists for exhibitor {} and attendee {}. Updating lead {}",
                    exhibitorId, attendee.getId(), lead.getId());

            if (request.getStatus() != null) {
                lead.setStatus(request.getStatus());
            }
            if (request.getNotes() != null && !request.getNotes().isBlank()) {
                lead.setNotes(request.getNotes().trim());
            }
            if (request.getInterests() != null && !request.getInterests().isEmpty()) {
                lead.setInterestsJson(writeJson(request.getInterests()));
            }
            if (request.getSource() != null && !request.getSource().isBlank()) {
                lead.setSource(request.getSource().trim());
            }
            lead.setSyncStatus("synced");
        } else {
            String leadId = "lead_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            LeadStatus status = request.getStatus() != null ? request.getStatus() : LeadStatus.NEW;
            String source = (request.getSource() != null && !request.getSource().isBlank())
                    ? request.getSource().trim()
                    : "Directory Lead Capture";
            String notes = request.getNotes() != null ? request.getNotes().trim() : null;

            lead = new LeadEntity(leadId, exhibitorId, attendee.getId(), status, source, notes);
            if (request.getInterests() != null && !request.getInterests().isEmpty()) {
                lead.setInterestsJson(writeJson(request.getInterests()));
            }
            lead.setSyncStatus("synced");
            log.info("Captured new lead {} for exhibitor {} (attendee {})", leadId, exhibitorId, attendee.getId());
        }

        lead.setAttendee(attendee);
        LeadEntity savedLead = leadRepository.save(lead);
        return toLeadDto(savedLead, attendee, exhibitor.getBooth());
    }

    public LeadDto updateLeadStatus(String exhibitorId, String leadId, UpdateLeadStatusRequest request) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);

        LeadEntity lead = leadRepository.findWithAttendeeByIdAndExhibitorId(leadId, exhibitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found or access denied: " + leadId));

        if (request.getStatus() != null) {
            lead.setStatus(request.getStatus());
        }
        if (request.getNotes() != null) {
            lead.setNotes(request.getNotes().trim());
        }
        lead.setSyncStatus("synced");

        LeadEntity updated = leadRepository.save(lead);
        return toLeadDto(updated, exhibitor.getBooth());
    }

    @Transactional(readOnly = true)
    public byte[] exportLeadsCsv(String exhibitorId) {
        List<LeadEntity> leads = leadRepository.findAllWithAttendeeByExhibitorId(exhibitorId);

        StringBuilder sb = new StringBuilder();
        // RFC 4180 CSV Header
        sb.append("\"Name\",\"Company\",\"Designation\",\"Email\",\"Mobile\",\"Status\",\"Interests\",\"Captured At\",\"Notes\"\r\n");

        for (LeadEntity lead : leads) {
            AttendeeEntity attendee = lead.getAttendee();
            if (attendee == null && lead.getAttendeeId() != null) {
                attendee = attendeeRepository.findById(lead.getAttendeeId()).orElse(null);
            }
            PrivacyConsentEntity privacy = (attendee != null) ? attendee.getPrivacyConsent() : null;

            boolean canShareEmail = privacy == null || privacy.isShareEmail();
            boolean canSharePhone = privacy == null || privacy.isSharePhone();

            String name = (attendee != null) ? attendee.getName() : "";
            String company = (attendee != null) ? attendee.getCompany() : "";
            String designation = (attendee != null && attendee.getDesignation() != null) ? attendee.getDesignation() : "";
            String email = (attendee != null && canShareEmail) ? attendee.getEmail() : "contact@eventsphere.internal";
            String mobile = (attendee != null && canSharePhone) ? attendee.getMobile() : "+91 98765 00000";
            String status = lead.getStatus().getValue();
            List<String> interestsList = readStringList(lead.getInterestsJson());
            String interests = String.join("; ", interestsList);
            String capturedAt = lead.getCapturedAt().toString();
            String notes = (lead.getNotes() != null) ? lead.getNotes() : "";

            sb.append(escapeCsvField(name)).append(",")
              .append(escapeCsvField(company)).append(",")
              .append(escapeCsvField(designation)).append(",")
              .append(escapeCsvField(email)).append(",")
              .append(escapeCsvField(mobile)).append(",")
              .append(escapeCsvField(status)).append(",")
              .append(escapeCsvField(interests)).append(",")
              .append(escapeCsvField(capturedAt)).append(",")
              .append(escapeCsvField(notes)).append("\r\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public List<ProductDto> getProducts(String exhibitorId) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);
        List<ProductEntity> products = productRepository.findAllByExhibitorIdOrderByCreatedAtAsc(exhibitorId);
        return products.stream()
                .map(p -> toProductDto(p, exhibitor.getCompany(), exhibitor.getBooth()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductDto getProductById(String exhibitorId, String productId) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);
        ProductEntity product = productRepository.findByIdAndExhibitorId(productId, exhibitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        return toProductDto(product, exhibitor.getCompany(), exhibitor.getBooth());
    }

    public ProductDto createProduct(String exhibitorId, CreateProductRequest request) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);

        String productId = "prod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        product.setExhibitorId(exhibitorId);
        product.setName(request.getName().trim());
        product.setTagline(request.getTagline().trim());
        product.setCategory(request.getCategory().trim());
        product.setOverview(request.getOverview().trim());
        product.setImageUrl((request.getImageUrl() != null && !request.getImageUrl().isBlank())
                ? request.getImageUrl().trim()
                : "/images/exhibitors/" + exhibitor.getId().toLowerCase() + ".png");
        product.setFeaturesJson(writeJson(request.getFeatures()));
        product.setBenefitsJson(writeJson(request.getBenefits()));
        product.setUseCasesJson(writeJson(request.getUseCases()));
        product.setWebsiteUrl(request.getWebsiteUrl());
        product.setBrochureUrl(request.getBrochureUrl());
        product.setViews(0);
        product.setEnquiries(0);
        product.setInterestedVisitorsCount(0);

        ProductEntity saved = productRepository.save(product);
        log.info("Created product {} for exhibitor {}", saved.getId(), exhibitorId);
        return toProductDto(saved, exhibitor.getCompany(), exhibitor.getBooth());
    }

    public ProductDto updateProduct(String exhibitorId, String productId, UpdateProductRequest request) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);

        ProductEntity product = productRepository.findByIdAndExhibitorId(productId, exhibitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found or access denied: " + productId));

        product.setName(request.getName().trim());
        if (request.getTagline() != null && !request.getTagline().isBlank()) {
            product.setTagline(request.getTagline().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            product.setCategory(request.getCategory().trim());
        }
        if (request.getOverview() != null && !request.getOverview().isBlank()) {
            product.setOverview(request.getOverview().trim());
        }
        if (request.getFeatures() != null) {
            product.setFeaturesJson(writeJson(request.getFeatures()));
        }
        if (request.getBenefits() != null) {
            product.setBenefitsJson(writeJson(request.getBenefits()));
        }
        if (request.getUseCases() != null) {
            product.setUseCasesJson(writeJson(request.getUseCases()));
        }
        if (request.getWebsiteUrl() != null) {
            product.setWebsiteUrl(request.getWebsiteUrl().trim());
        }
        if (request.getBrochureUrl() != null) {
            product.setBrochureUrl(request.getBrochureUrl().trim());
        }
        if (request.getImageUrl() != null) {
            product.setImageUrl(request.getImageUrl().trim());
        }

        ProductEntity updated = productRepository.save(product);
        log.info("Updated product {} for exhibitor {}", productId, exhibitorId);
        return toProductDto(updated, exhibitor.getCompany(), exhibitor.getBooth());
    }

    public void deleteProduct(String exhibitorId, String productId) {
        ProductEntity product = productRepository.findByIdAndExhibitorId(productId, exhibitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found or access denied: " + productId));
        productRepository.delete(product);
        log.info("Deleted product {} for exhibitor {}", productId, exhibitorId);
    }

    @org.springframework.cache.annotation.CacheEvict(value = "exhibitors", allEntries = true)
    public ExhibitorStatsDto updateBrochure(String exhibitorId, UpdateBrochureRequest request) {
        ExhibitorEntity exhibitor = getExhibitorOrThrow(exhibitorId);
        exhibitor.setBrochureUrl(request.getBrochureUrl());
        exhibitor.setBrochureName(request.getBrochureName());
        exhibitorRepository.save(exhibitor);
        log.info("Updated company brochure for exhibitor {}", exhibitorId);
        return getStats(exhibitorId);
    }

    @org.springframework.cache.annotation.Cacheable(value = "exhibitors", key = "#eventId")
    @Transactional(readOnly = true)
    public List<PublicExhibitorDto> getPublicExhibitors(String eventId) {
        List<ExhibitorEntity> list = exhibitorRepository.findAllByEventId(eventId);
        return list.stream()
                .filter(e -> !e.getId().equalsIgnoreCase("exh-truechip"))
                .map(e -> new PublicExhibitorDto(
                        e.getId(),
                        e.getCompany(),
                        e.getBooth(),
                        e.getCategory(),
                        e.getLogoUrl(),
                        e.getBrochureUrl(),
                        e.getBrochureName(),
                        e.getStatus()
                )).toList();
    }

    @Transactional(readOnly = true)
    public PublicExhibitorDto getPublicExhibitor(String exhibitorId) {
        ExhibitorEntity e = exhibitorRepository.findById(exhibitorId)
                .or(() -> "exh-2".equalsIgnoreCase(exhibitorId) ? exhibitorRepository.findById("exh-truechip") : Optional.empty())
                .or(() -> "exh-truechip".equalsIgnoreCase(exhibitorId) ? exhibitorRepository.findById("exh-2") : Optional.empty())
                .orElseThrow(() -> new ResourceNotFoundException("Exhibitor", exhibitorId));
        return new PublicExhibitorDto(
                e.getId(),
                e.getCompany(),
                e.getBooth(),
                e.getCategory(),
                e.getLogoUrl(),
                e.getBrochureUrl(),
                e.getBrochureName(),
                e.getStatus()
        );
    }

    @Transactional(readOnly = true)
    public List<ProductDto> getPublicProducts(String exhibitorId) {
        ExhibitorEntity e = exhibitorRepository.findById(exhibitorId)
                .or(() -> "exh-2".equalsIgnoreCase(exhibitorId) ? exhibitorRepository.findById("exh-truechip") : Optional.empty())
                .or(() -> "exh-truechip".equalsIgnoreCase(exhibitorId) ? exhibitorRepository.findById("exh-2") : Optional.empty())
                .orElseThrow(() -> new ResourceNotFoundException("Exhibitor", exhibitorId));

        List<String> targetIds = ("exh-2".equalsIgnoreCase(exhibitorId) || "exh-truechip".equalsIgnoreCase(exhibitorId))
                ? List.of("exh-2", "exh-truechip")
                : List.of(exhibitorId);

        List<ProductEntity> products = productRepository.findAllByExhibitorIdInOrderByCreatedAtAsc(targetIds);
        return products.stream()
                .map(p -> toProductDto(p, e.getCompany(), e.getBooth()))
                .toList();
    }

    @Transactional
    public void recordProductEnquiry(String productId, String attendeeId) {
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        product.setEnquiries(product.getEnquiries() + 1);
        productRepository.save(product);
        log.info("Recorded enquiry for product {} by attendee {}", productId, attendeeId);
    }

    // Helper methods
    public ExhibitorEntity getExhibitorOrThrow(String exhibitorId) {
        return exhibitorRepository.findById(exhibitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Exhibitor", exhibitorId));
    }

    private LeadDto toLeadDto(LeadEntity lead, String boothId) {
        AttendeeEntity attendee = lead.getAttendee();
        if (attendee == null && lead.getAttendeeId() != null) {
            attendee = attendeeRepository.findById(lead.getAttendeeId()).orElse(null);
        }
        return toLeadDto(lead, attendee, boothId);
    }

    private LeadDto toLeadDto(LeadEntity lead, AttendeeEntity attendee, String boothId) {
        PrivacyConsentEntity privacy = (attendee != null) ? attendee.getPrivacyConsent() : null;
        boolean canShareEmail = privacy == null || privacy.isShareEmail();
        boolean canSharePhone = privacy == null || privacy.isSharePhone();
        boolean canShareLinkedIn = privacy == null || privacy.isShareLinkedIn();

        String name = (attendee != null) ? attendee.getName() : "Unknown Attendee";
        String avatar = (attendee != null) ? attendee.getAvatarUrl() : null;
        String company = (attendee != null) ? attendee.getCompany() : "";
        String designation = (attendee != null && attendee.getDesignation() != null) ? attendee.getDesignation() : "";
        String email = (attendee != null && canShareEmail) ? attendee.getEmail() : "contact@eventsphere.internal";
        String mobile = (attendee != null && canSharePhone) ? attendee.getMobile() : "+91 98765 00000";
        String linkedin = (attendee != null && canShareLinkedIn) ? (attendee.getLinkedinUrl() != null ? attendee.getLinkedinUrl() : "") : "";

        return new LeadDto(
                lead.getId(),
                lead.getAttendeeId(),
                name,
                avatar,
                company,
                designation,
                email,
                mobile,
                linkedin,
                lead.getSource(),
                lead.getStatus(),
                readStringList(lead.getInterestsJson()),
                lead.getNotes(),
                lead.getCapturedAt().toString(),
                boothId,
                readStringList(lead.getProductsViewedJson()),
                lead.getSyncStatus()
        );
    }

    private ProductDto toProductDto(ProductEntity product, String companyName, String booth) {
        ProductDto.ProductResourcesDto resources = new ProductDto.ProductResourcesDto(
                product.getBrochureUrl(),
                null,
                product.getWebsiteUrl()
        );

        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getTagline(),
                companyName,
                product.getCategory(),
                booth,
                product.getImageUrl(),
                product.getViews(),
                product.getEnquiries(),
                product.getInterestedVisitorsCount(),
                product.getOverview(),
                readStringList(product.getFeaturesJson()),
                readStringList(product.getBenefitsJson()),
                readStringList(product.getUseCasesJson()),
                resources
        );
    }

    private String writeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            return "[]";
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON string list: {}", json);
            return new ArrayList<>();
        }
    }

    private String escapeCsvField(String field) {
        if (field == null) return "\"\"";
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
