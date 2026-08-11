package com.fieldservice.domain.workorder.lifecycle;

/**
 * Test-only bridge giving test code access to the package-private
 * {@link WorkOrderTransitionTable}.
 * Lives in the same package as {@code WorkOrderTransitionTable} so it can
 * instantiate and return it without requiring the class to be public.
 */
public class WorkOrderTransitionTableAccessor {

    public WorkOrderTransitionPort table() {
        return new WorkOrderTransitionTable();
    }
}
