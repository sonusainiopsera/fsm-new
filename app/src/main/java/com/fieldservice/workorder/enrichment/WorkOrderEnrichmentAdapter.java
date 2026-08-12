package com.fieldservice.workorder.enrichment;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.asset.repository.AssetRepository;
import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.customer.repository.CustomerAccountRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class WorkOrderEnrichmentAdapter implements WorkOrderEnrichmentPort {

    private final WorkOrderRepository workOrderRepository;
    private final AssetRepository assetRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;

    WorkOrderEnrichmentAdapter(WorkOrderRepository workOrderRepository,
                               AssetRepository assetRepository,
                               CustomerAccountRepository customerAccountRepository,
                               ScopedQueryExecutor scopedQueryExecutor) {
        this.workOrderRepository       = workOrderRepository;
        this.assetRepository           = assetRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.scopedQueryExecutor       = scopedQueryExecutor;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkOrderContextData> loadContext(UUID workOrderId, AccessScope scope, int maxPriorWorkOrders) {
        Optional<WorkOrder> woOpt = scopedQueryExecutor.findById(workOrderRepository, workOrderId, scope, WorkOrder.class);
        if (woOpt.isEmpty()) {
            return Optional.empty();
        }
        WorkOrder wo = woOpt.get();

        Asset asset = (wo.getAssetId() != null)
                ? assetRepository.findById(wo.getAssetId()).orElse(null)
                : null;

        Site site = wo.getSite();

        CustomerAccount customer = (site != null && site.getCustomerId() != null)
                ? customerAccountRepository.findById(site.getCustomerId()).orElse(null)
                : null;

        List<PriorServiceEntry> history = List.of();
        if (wo.getAssetId() != null) {
            Specification<WorkOrder> priorSpec = buildPriorSpec(wo.getAssetId(), workOrderId);
            PageRequest pageable = PageRequest.of(0, maxPriorWorkOrders,
                    Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
            Page<WorkOrder> priorPage = scopedQueryExecutor.findAll(
                    workOrderRepository, priorSpec, pageable, scope, WorkOrder.class);
            history = priorPage.getContent().stream()
                    .map(w -> new PriorServiceEntry(
                            w.getId(), w.getReference(),
                            w.getFaultCode(), w.getFaultCategory(), w.getDescription()))
                    .toList();
        }

        return Optional.of(new WorkOrderContextData(
                wo.getId(),
                wo.getReference(),
                asset != null ? asset.getId() : null,
                asset != null ? asset.getModel() : null,
                asset != null ? asset.getManufacturer() : null,
                asset != null ? asset.getSerialNumber() : null,
                asset != null ? asset.getCategory() : null,
                wo.getDescription(),
                wo.getFaultCode(),
                wo.getFaultCategory(),
                site != null ? site.getName() : null,
                site != null ? site.getAddressLine1() : null,
                site != null ? site.getCity() : null,
                site != null ? site.getPostcode() : null,
                customer != null ? customer.getName() : null,
                customer != null ? customer.getLegalName() : null,
                customer != null ? customer.getPrimaryContactName() : null,
                customer != null ? customer.getContactEmail() : null,
                customer != null ? customer.getPhone() : null,
                customer != null ? customer.getPrimaryContactEmail() : null,
                customer != null ? customer.getPrimaryContactPhone() : null,
                customer != null ? customer.getBillingAddress() : null,
                history));
    }

    private static Specification<WorkOrder> buildPriorSpec(UUID assetId, UUID excludeWorkOrderId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("assetId"), assetId),
                cb.notEqual(root.get("id"), excludeWorkOrderId),
                root.get("state").in(WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED));
    }
}
