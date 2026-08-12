/**
 * MSW handlers for the photo analysis endpoints.
 *
 * Provides fixtures for:
 *   - POST /analysis 200 success with attribution
 *   - POST /analysis 503 AI provider unavailable
 *   - POST /analysis 503 feature flag disabled
 *   - POST /analysis 429 daily cap reached
 *   - POST /analysis 403 cross-technician access
 *   - POST /description 200 success with override classification
 */

const WORK_ORDER_ID = 'wo-001';
const PHOTO_ID      = 'photo-001';
const INTERACTION_ID = 'ia-00000001-0000-7181-0000-000000000001';

/** 200 successful analysis draft. */
export function photoAnalysisSuccessFixture() {
  return {
    status: 200,
    body: {
      interactionId:        INTERACTION_ID,
      suggestedDescription: 'Relay on main circuit board showing signs of heat damage with visible burn marks on terminals.',
      advisory:             true,
      source:               'AI',
      provider:             'gpt-4-vision-preview',
      basis:                ['PHOTO_CONTENT'],
      degraded:             false,
      degradedCode:         null,
    },
  };
}

/** 200 analysis with empty suggestion (provider returned no useful caption). */
export function photoAnalysisEmptySuggestionFixture() {
  return {
    status: 200,
    body: {
      interactionId:        INTERACTION_ID,
      suggestedDescription: null,
      advisory:             false,
      source:               'AI',
      provider:             'gpt-4-vision-preview',
      basis:                [],
      degraded:             true,
      degradedCode:         'AI_PROVIDER_UNAVAILABLE',
    },
  };
}

/** 503 AI provider unavailable. */
export function photoAnalysisDegradedFixture() {
  return {
    status: 503,
    body: {
      status:    503,
      code:      'AI_PROVIDER_UNAVAILABLE',
      message:   'Photo analysis is temporarily unavailable. Please describe the fault manually.',
      degraded:  true,
      traceId:   'test-degraded-001',
    },
  };
}

/** 503 feature flag disabled. */
export function photoAnalysisFeatureDisabledFixture() {
  return {
    status: 503,
    body: {
      status:    503,
      code:      'AI_FEATURE_DISABLED',
      message:   'Photo analysis is currently unavailable.',
      degraded:  true,
      traceId:   'test-flag-off-001',
    },
  };
}

/** 429 daily AI cap reached. */
export function photoAnalysisCappedFixture() {
  return {
    status: 429,
    headers: { 'Retry-After': '3600' },
    body: {
      status:    429,
      code:      'AI_DAILY_LIMIT_REACHED',
      message:   'Daily photo analysis limit reached. Try again after 01:00 UTC.',
      degraded:  true,
      traceId:   'test-capped-001',
    },
  };
}

/** 403 cross-technician access attempt. */
export function photoAnalysisForbiddenFixture() {
  return {
    status: 403,
    body: {
      status:  403,
      code:    'PHOTO_ACCESS_DENIED',
      message: 'Access denied',
      traceId: 'test-forbidden-001',
    },
  };
}

/** 200 description recorded — accepted unchanged. */
export function descriptionAcceptedUnchangedFixture() {
  return {
    status: 200,
    body: {
      description:             'Relay on main circuit board showing signs of heat damage with visible burn marks on terminals.',
      overrideClassification:  'ACCEPTED_UNCHANGED',
      similarityScore:         1.0,
    },
  };
}

/** 200 description recorded — lightly edited. */
export function descriptionLightlyEditedFixture() {
  return {
    status: 200,
    body: {
      description:             'Relay on main circuit board shows heat damage and burn marks on terminals.',
      overrideClassification:  'LIGHTLY_EDITED',
      similarityScore:         0.7143,
    },
  };
}

/** 200 description recorded — discarded (technician typed their own). */
export function descriptionDiscardedFixture() {
  return {
    status: 200,
    body: {
      description:             null,
      overrideClassification:  'DISCARDED',
      similarityScore:         0.0,
    },
  };
}

/** Fake IDs used in test assertions. */
export { WORK_ORDER_ID, PHOTO_ID, INTERACTION_ID };
