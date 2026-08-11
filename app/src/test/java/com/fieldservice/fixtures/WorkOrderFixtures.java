package com.fieldservice.fixtures;

import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;

import java.time.Duration;
import java.util.UUID;

/**
 * Object-mother for {@link WorkOrder} and {@link Assignment} test fixtures.
 *
 * <p>Each factory method produces a work order in the given lifecycle state with
 * internally consistent field values:
 * <ul>
 *   <li>NEW / CANCELLED — no technician, no SLA deadline.</li>
 *   <li>ASSIGNED / EN_ROUTE / IN_PROGRESS / ON_HOLD — technician present, SLA deadline set.</li>
 *   <li>COMPLETED — description includes labour time entry (lifecycle consistency, WO-201).</li>
 *   <li>CLOSED — description includes parts-consumption reconciliation (lifecycle consistency).</li>
 * </ul>
 *
 * <p>SLA deadlines are derived from {@link DeterministicIds#EPOCH} using standard offsets:
 * CRITICAL=4h, HIGH=8h, MEDIUM=24h, LOW=72h. Override via {@link Builder#withSlaDeadlineOffset}.
 */
public final class WorkOrderFixtures {

    private WorkOrderFixtures() {}

    // -----------------------------------------------------------------------
    // Per-state factory methods
    // -----------------------------------------------------------------------

    public static Builder inState(WorkOrderState state) { return new Builder(state); }

    public static Builder newWorkOrder() { return new Builder(WorkOrderState.NEW); }
    public static Builder assigned()     { return new Builder(WorkOrderState.ASSIGNED); }
    public static Builder enRoute()      { return new Builder(WorkOrderState.EN_ROUTE); }
    public static Builder inProgress()   { return new Builder(WorkOrderState.IN_PROGRESS); }
    public static Builder onHold()       { return new Builder(WorkOrderState.ON_HOLD); }
    public static Builder completed()    { return new Builder(WorkOrderState.COMPLETED); }
    public static Builder closed()       { return new Builder(WorkOrderState.CLOSED); }
    public static Builder cancelled()    { return new Builder(WorkOrderState.CANCELLED); }

    // -----------------------------------------------------------------------
    // SLA deadline offsets by priority (standard field service targets)
    // -----------------------------------------------------------------------

    static Duration slaResolutionOffset(WorkOrderPriority priority) {
        return switch (priority) {
            case CRITICAL -> Duration.ofHours(4);
            case HIGH     -> Duration.ofHours(8);
            case MEDIUM   -> Duration.ofHours(24);
            case LOW      -> Duration.ofHours(72);
        };
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {

        private final WorkOrderState state;
        private UUID id = DeterministicIds.nextId();
        private Customer customer;
        private Site site;
        private UUID assignedTechnicianId;
        private WorkOrderPriority priority = WorkOrderPriority.MEDIUM;
        private String title;
        private String description;
        private Duration slaDeadlineOffset;

        private Builder(WorkOrderState state) {
            this.state = state;
            this.title = "Fixture work order [" + state + "]";
            if (requiresTechnician(state)) {
                this.slaDeadlineOffset = slaResolutionOffset(WorkOrderPriority.MEDIUM);
            }
        }

        public Builder withId(UUID id)                        { this.id = id;                       return this; }
        public Builder withCustomer(Customer c)               { this.customer = c;                  return this; }
        public Builder withSite(Site s)                       { this.site = s;                      return this; }
        public Builder withAssignedTechnicianId(UUID techId)  { this.assignedTechnicianId = techId; return this; }
        public Builder withPriority(WorkOrderPriority p) {
            this.priority = p;
            if (requiresTechnician(state)) this.slaDeadlineOffset = slaResolutionOffset(p);
            return this;
        }
        public Builder withTitle(String title)                { this.title = title;                 return this; }
        public Builder withDescription(String desc)           { this.description = desc;            return this; }
        public Builder withSlaDeadlineOffset(Duration offset) { this.slaDeadlineOffset = offset;   return this; }

        public WorkOrderResult build() {
            WorkOrder wo = new WorkOrder();
            wo.setId(id);
            wo.setState(state);
            wo.setPriority(priority);
            wo.setTitle(title);
            wo.setDescription(description != null ? description : buildDescription());

            if (customer != null) wo.setCustomer(customer);
            if (site != null) wo.setSite(site);

            UUID techId = resolveTechnicianId();
            wo.setAssignedTechnicianId(techId);

            if (slaDeadlineOffset != null) {
                wo.setSlaDeadline(DeterministicIds.EPOCH.plus(slaDeadlineOffset));
            }

            Assignment assignment = null;
            if (techId != null) {
                assignment = new Assignment();
                assignment.setWorkOrderId(id);
                assignment.setTechnicianId(techId);
                assignment.setCurrent(state != WorkOrderState.CLOSED
                        && state != WorkOrderState.COMPLETED
                        && state != WorkOrderState.CANCELLED);
            }

            return new WorkOrderResult(wo, assignment);
        }

        private UUID resolveTechnicianId() {
            if (assignedTechnicianId != null) return assignedTechnicianId;
            if (requiresTechnician(state)) {
                // Stable placeholder from V100 fixtures — overrideable via withAssignedTechnicianId
                return UUID.fromString("00000000-0000-0000-0000-000000000011");
            }
            return null;
        }

        private String buildDescription() {
            return switch (state) {
                case COMPLETED -> "Work completed. Labour time: 2h 30m recorded. Parts installed and function tested.";
                case CLOSED    -> "Work order closed. Parts consumption reconciled. Invoice reference: INV-FX-001.";
                default        -> "Fixture work order in state " + state + ".";
            };
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static boolean requiresTechnician(WorkOrderState state) {
        return switch (state) {
            case ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD, COMPLETED, CLOSED -> true;
            case NEW, CANCELLED -> false;
        };
    }

    // -----------------------------------------------------------------------
    // Value carrier
    // -----------------------------------------------------------------------

    public record WorkOrderResult(WorkOrder workOrder, Assignment assignment) {}
}
