package com.fieldservice.workorder.application;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.WorkOrderRequiredPart;
import com.fieldservice.domain.inventory.WorkOrderRequiredPartRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderCompetency;
import com.fieldservice.domain.workorder.WorkOrderCompetencyRepository;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.workorder.api.dto.TechnicianJobDetailResponse;
import com.fieldservice.workorder.holds.HoldReasonResponse;
import com.fieldservice.workorder.holds.HoldReasonService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the full technician job detail projection (WO-156 AC-1).
 *
 * <p>Two JDBC round-trips beyond the work order load:
 * <ol>
 *   <li>Required certifications from {@code work_order_competency}.</li>
 *   <li>Expected parts from {@code work_order_required_part} with a batch part lookup.</li>
 * </ol>
 * The asset and customer are loaded via the already-fetched work order's relations.
 */
@Service
@Transactional(readOnly = true)
public class TechnicianJobDetailService {

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderCompetencyRepository competencyRepository;
    private final WorkOrderRequiredPartRepository requiredPartRepository;
    private final PartRepository partRepository;
    private final AssetRepository assetRepository;
    private final HoldReasonService holdReasonService;
    private final AllowedTransitionResolver transitionResolver;

    public TechnicianJobDetailService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            WorkOrderCompetencyRepository competencyRepository,
            WorkOrderRequiredPartRepository requiredPartRepository,
            PartRepository partRepository,
            AssetRepository assetRepository,
            HoldReasonService holdReasonService,
            AllowedTransitionResolver transitionResolver) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.competencyRepository = competencyRepository;
        this.requiredPartRepository = requiredPartRepository;
        this.partRepository = partRepository;
        this.assetRepository = assetRepository;
        this.holdReasonService = holdReasonService;
        this.transitionResolver = transitionResolver;
    }

    @PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN')")
    public TechnicianJobDetailResponse getDetail(UUID workOrderId) {
        WorkOrder wo = scopedQueryExecutor.findById(WorkOrder.class, workOrderId, workOrderRepository);

        // Certifications
        List<String> certs = competencyRepository.findByWorkOrderId(workOrderId).stream()
                .map(WorkOrderCompetency::getCompetencyCode)
                .sorted()
                .toList();

        // Expected parts
        List<WorkOrderRequiredPart> requiredParts = requiredPartRepository.findByWorkOrderId(workOrderId);
        List<TechnicianJobDetailResponse.ExpectedPart> expectedParts = buildExpectedParts(requiredParts);

        // Asset
        Asset asset = wo.getAssetId() != null
                ? assetRepository.findById(wo.getAssetId()).orElse(null)
                : null;

        // Contact (masked)
        String contactName = wo.getCustomer() != null ? wo.getCustomer().getPrimaryContactName() : null;
        String rawPhone = wo.getCustomer() != null ? wo.getCustomer().getContactPhone() : null;
        String maskedPhone = ContactMaskingHelper.maskPhone(rawPhone);

        // Site
        var site = wo.getSite();

        // SLA at-risk flag
        boolean atRisk = wo.getAtRiskAt() != null
                && wo.getAtRiskAt().isBefore(Instant.now());

        // Server-driven transitions
        Set<String> allowedTransitions = transitionResolver.resolveForCaller(wo.getState());

        // Hold reasons (embedded for convenience)
        List<HoldReasonResponse> holdReasons = holdReasonService.getActiveReasons();

        return new TechnicianJobDetailResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState() != null ? wo.getState().name() : null,
                wo.getPriority() != null ? wo.getPriority().name() : null,
                wo.getFaultDescription(),
                // site
                site != null ? site.getName() : null,
                site != null ? site.getAddress() : null,
                site != null ? site.getPostcode() : null,
                site != null ? site.getAccessNotes() : null,
                // contact
                contactName,
                maskedPhone,
                // asset
                asset != null ? asset.getId() : null,
                asset != null ? asset.getAssetTag() : null,
                asset != null ? asset.getName() : null,
                asset != null ? asset.getModel() : null,
                asset != null ? asset.getManufacturer() : null,
                // requirements
                certs,
                expectedParts,
                // SLA
                wo.getResponseDueAt(),
                wo.getResolutionDueAt(),
                wo.getAtRiskAt(),
                atRisk,
                // server-driven action
                allowedTransitions,
                holdReasons,
                wo.getVersion()
        );
    }

    private List<TechnicianJobDetailResponse.ExpectedPart> buildExpectedParts(
            List<WorkOrderRequiredPart> requiredParts) {
        if (requiredParts.isEmpty()) {
            return List.of();
        }
        List<UUID> partIds = requiredParts.stream()
                .map(WorkOrderRequiredPart::getPartId)
                .toList();

        Map<UUID, Part> partsById = partRepository.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, p -> p));

        return requiredParts.stream()
                .map(rp -> {
                    Part p = partsById.get(rp.getPartId());
                    return new TechnicianJobDetailResponse.ExpectedPart(
                            rp.getPartId(),
                            p != null ? p.getPartNumber() : null,
                            p != null ? p.getDescription() : null,
                            rp.getRequiredQuantity()
                    );
                })
                .toList();
    }
}
