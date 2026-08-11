package com.fieldservice.privacy.internal;

/** Composite key into the DSAR transition table: the current state plus the requested event. */
record DsarTransitionKey(DsarState fromState, DsarEvent event) {}
