package com.fieldservice.fixtures;

import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Object-mother builders for {@link WorkOrder} and {@link Assignment}.
 *
 * <p>Produces internally consistent records: ASSIGNED/EN_ROUTE/IN_PROGRESS/ON_HOLD
 * states carry an assignment with no releasedAt; COMPLETED and CLOSED carry an
 * assignment with a releasedAt (representing logged labour time).  NEW and
 * CANCELLED have no assignment.
 */
public final class WorkOrderFixtures {

    private WorkOrderFixtures() {}

    // ---- WorkOrder builder ---------------------------------------------------

    public static final class Builder {

        private UUID            id             = DeterministicIds.next();
        private String          reference      = "WO-FIX-" + String.format("%04d", counter.incrementAndGet());
        private WorkOrderStatus status         = WorkOrderStatus.NEW;
        private String          priority       = "MEDIUM";
        private Site            site;
        private UUID            technicianId   = null;
        private String          description    = "Fixture work order";

        // For assignment lifecycle
        private UUID assignmentId    = DeterministicIds.next();
        private boolean withAssignment = false;

        private Builder(Site site) {
            this.site = site;
        }

        public Builder withId(UUID id)                { this.id = id;                return this; }
        public Builder withReference(String ref)      { this.reference = ref;        return this; }
        public Builder withPriority(String priority)  { this.priority = priority;    return this; }
        public Builder withTechnicianId(UUID techId)  { this.technicianId = techId;  return this; }
        public Builder withDescription(String desc)   { this.description = desc;     return this; }
        public Builder inStatus(WorkOrderStatus s)    { this.status = s;             return this; }

        /** Produces a NEW work order with no technician. */
        public Builder asNew() {
            this.status       = WorkOrderStatus.NEW;
            this.technicianId = null;
            return this;
        }

        /** Produces an ASSIGNED work order — requires a technicianId. */
        public Builder asAssigned(UUID technicianId) {
            this.status       = WorkOrderStatus.ASSIGNED;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces an EN_ROUTE work order. */
        public Builder asEnRoute(UUID technicianId) {
            this.status       = WorkOrderStatus.EN_ROUTE;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces an IN_PROGRESS work order. */
        public Builder asInProgress(UUID technicianId) {
            this.status       = WorkOrderStatus.IN_PROGRESS;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces an ON_HOLD work order. */
        public Builder asOnHold(UUID technicianId) {
            this.status       = WorkOrderStatus.ON_HOLD;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces a COMPLETED work order with logged labour time. */
        public Builder asCompleted(UUID technicianId) {
            this.status       = WorkOrderStatus.COMPLETED;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces a CLOSED work order with reconciled labour time. */
        public Builder asClosed(UUID technicianId) {
            this.status       = WorkOrderStatus.CLOSED;
            this.technicianId = technicianId;
            this.withAssignment = true;
            return this;
        }

        /** Produces a CANCELLED work order with no technician. */
        public Builder asCancelled() {
            this.status       = WorkOrderStatus.CANCELLED;
            this.technicianId = null;
            return this;
        }

        /** Builds the work order entity only (no assignment). */
        public WorkOrder build() {
            WorkOrder wo = new WorkOrder(id, reference, status, priority, site, technicianId);
            setField(WorkOrder.class, wo, "description", description);
            return wo;
        }

        /**
         * Builds the work order plus any accompanying entities required for lifecycle
         * consistency (assignment with releasedAt for COMPLETED/CLOSED).
         */
        public WorkOrderGraph buildGraph() {
            WorkOrder wo = build();
            if (!withAssignment || technicianId == null) {
                return new WorkOrderGraph(wo, null);
            }

            Assignment assignment = new Assignment(wo.getId(), technicianId);
            setField(Assignment.class, assignment, "id", assignmentId);
            setField(Assignment.class, assignment, "assignedAt",
                    DeterministicIds.FIXED_INSTANT.minus(2, ChronoUnit.HOURS));

            if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CLOSED) {
                // releasedAt = assignedAt + 90 minutes (logged labour time)
                Instant releasedAt = DeterministicIds.FIXED_INSTANT
                        .minus(2, ChronoUnit.HOURS)
                        .plus(90, ChronoUnit.MINUTES);
                setField(Assignment.class, assignment, "releasedAt", releasedAt);
            }

            return new WorkOrderGraph(wo, assignment);
        }
    }

    // ---- WorkOrderGraph: work order + optional assignment --------------------

    public record WorkOrderGraph(WorkOrder workOrder, Assignment assignment) {
        public boolean hasAssignment() { return assignment != null; }
    }

    // ---- Static factories ---------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger counter =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static Builder forSite(Site site) {
        return new Builder(site);
    }

    public static WorkOrderGraph newOrder(Site site) {
        return forSite(site).asNew().buildGraph();
    }

    public static WorkOrderGraph assigned(Site site, UUID technicianId) {
        return forSite(site).asAssigned(technicianId).buildGraph();
    }

    public static WorkOrderGraph enRoute(Site site, UUID technicianId) {
        return forSite(site).asEnRoute(technicianId).buildGraph();
    }

    public static WorkOrderGraph inProgress(Site site, UUID technicianId) {
        return forSite(site).asInProgress(technicianId).buildGraph();
    }

    public static WorkOrderGraph onHold(Site site, UUID technicianId) {
        return forSite(site).asOnHold(technicianId).buildGraph();
    }

    public static WorkOrderGraph completed(Site site, UUID technicianId) {
        return forSite(site).asCompleted(technicianId).buildGraph();
    }

    public static WorkOrderGraph closed(Site site, UUID technicianId) {
        return forSite(site).asClosed(technicianId).buildGraph();
    }

    public static WorkOrderGraph cancelled(Site site) {
        return forSite(site).asCancelled().buildGraph();
    }

    // ---- Reflection helper --------------------------------------------------

    static void setField(Class<?> cls, Object target, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Fixture reflection failed: " + cls.getSimpleName() + "." + name, e);
        }
    }
}
