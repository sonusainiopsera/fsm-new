package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarState;

record DsarTransitionKey(DsarState fromState, DsarEvent event) {}
