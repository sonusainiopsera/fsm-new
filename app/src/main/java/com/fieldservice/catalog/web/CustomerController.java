package com.fieldservice.catalog.web;

import com.fieldservice.catalog.api.CustomerRef;
import com.fieldservice.catalog.application.CatalogService;
import com.fieldservice.catalog.application.CreateCustomerCommand;
import com.fieldservice.catalog.web.dto.CreateCustomerRequest;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for the customer aggregate.
 *
 * <p>CUSTOMER and TECHNICIAN callers are denied at the service layer via
 * {@code @PreAuthorize}. All other authenticated roles may read and write customers.
 *
 * <p>DELETE deactivates rather than deletes; physical removal is reserved for the GDPR path.
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Catalog - Customers", description = "Customer reference data")
public class CustomerController {

    private final CatalogService catalogService;

    public CustomerController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(operationId = "listCustomers", summary = "List customers (paginated, allow-listed sort)")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<CustomerRef>> listCustomers(
            @RequestParam(required = false) String q,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(catalogService.listCustomers(q, pageQuery, request));
    }

    @Operation(operationId = "getCustomer", summary = "Get a single customer by ID")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerRef> getCustomer(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getCustomer(id));
    }

    @Operation(operationId = "createCustomer", summary = "Create a new customer")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerRef> createCustomer(
            @Valid @RequestBody CreateCustomerRequest body,
            UriComponentsBuilder ucb) {
        CustomerRef created = catalogService.createCustomer(toCommand(body));
        URI location = ucb.path("/api/v1/customers/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(operationId = "updateCustomer", summary = "Update a customer")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerRef> updateCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody CreateCustomerRequest body) {
        return ResponseEntity.ok(catalogService.updateCustomer(id, toCommand(body)));
    }

    @Operation(operationId = "deactivateCustomer", summary = "Deactivate a customer (soft delete)")
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<Void> deactivateCustomer(@PathVariable UUID id) {
        catalogService.deactivateCustomer(id);
        return ResponseEntity.noContent().build();
    }

    private static CreateCustomerCommand toCommand(CreateCustomerRequest r) {
        return new CreateCustomerCommand(r.accountCode(), r.legalName(),
                r.primaryContactName(), r.primaryContactEmail(),
                r.primaryContactPhone(), r.billingAddress());
    }
}
