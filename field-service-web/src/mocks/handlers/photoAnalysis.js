/**
 * @fileoverview MSW-style mock fetch handlers for photo analysis endpoints (WO-181).
 *
 * Endpoints mocked:
 *   POST /api/v1/work-orders/:workOrderId/photos/:photoId/analysis    → AnalysisDraft
 *   POST /api/v1/work-orders/:workOrderId/photos/:photoId/description → DescriptionResult
 *
 * Usage in tests:
 *   vi.stubGlobal('fetch', createPhotoAnalysisFetch({ scenario: 'happy' }))
 */

const BASE = '/api/v1'

// ── Response helpers ──────────────────────────────────────────────────────────

function jsonResponse(body, status = 200, headers = {}) {
    const allHeaders = { 'content-type': 'application/json', ...headers }
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        headers: {
            get: (h) => allHeaders[h.toLowerCase()] ?? null,
            forEach: (fn) => Object.entries(allHeaders).forEach(([k, v]) => fn(v, k)),
        },
        json: () => Promise.resolve(body),
        text: () => Promise.resolve(JSON.stringify(body)),
        clone() { return jsonResponse(body, status, headers) },
    })
}

function errorResponse(code, message, status) {
    return jsonResponse({ code, message }, status)
}

// ── Fixture data ──────────────────────────────────────────────────────────────

const FIXTURE_INTERACTION_ID = '0195b4e0-0000-7000-a000-000000000001'

const HAPPY_DRAFT = {
    interactionId:        FIXTURE_INTERACTION_ID,
    suggestedDescription: 'Pump seal visibly split; oil pooling at the base. Immediate replacement required.',
    source:               'AI',
    advisory:             true,
    provider:             'vision-model',
}

const HAPPY_DESCRIPTION_RESULT = {
    overrideClassification: 'LIGHTLY_EDITED',
    similarityScore: 0.72,
}

// ── Route matchers ────────────────────────────────────────────────────────────

const ANALYSIS_REGEXP    = /\/api\/v1\/work-orders\/([^/]+)\/photos\/([^/]+)\/analysis$/
const DESCRIPTION_REGEXP = /\/api\/v1\/work-orders\/([^/]+)\/photos\/([^/]+)\/description$/

// ── Handler factories ─────────────────────────────────────────────────────────

/**
 * Creates a mock fetch function for photo analysis endpoints.
 *
 * @param {{ scenario?: 'happy' | 'degraded' | 'capped' | 'disabled' | 'no_suggestion' }} [opts]
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function createPhotoAnalysisFetch({ scenario = 'happy' } = {}) {
    return async function mockFetch(url, init = {}) {
        const method = (init.method ?? 'GET').toUpperCase()

        if (method === 'POST' && ANALYSIS_REGEXP.test(url)) {
            return handleAnalysis(scenario)
        }

        if (method === 'POST' && DESCRIPTION_REGEXP.test(url)) {
            return handleDescription(scenario)
        }

        // Pass-through unhandled requests
        return jsonResponse({ code: 'NOT_MOCKED', message: `No mock for ${method} ${url}` }, 501)
    }
}

function handleAnalysis(scenario) {
    switch (scenario) {
        case 'degraded':
            return errorResponse('AI_PROVIDER_UNAVAILABLE', 'AI provider is temporarily unavailable.', 503)

        case 'capped':
            return errorResponse('AI_DAILY_LIMIT_REACHED', 'Daily AI interaction limit reached.', 429)

        case 'disabled':
            return errorResponse('FEATURE_DISABLED', 'Photo analysis is not enabled.', 503)

        case 'no_suggestion':
            return jsonResponse({
                ...HAPPY_DRAFT,
                suggestedDescription: '',
            })

        case 'happy':
        default:
            return jsonResponse(HAPPY_DRAFT)
    }
}

function handleDescription(scenario) {
    if (scenario === 'degraded' || scenario === 'disabled') {
        return errorResponse('RECORD_FAILED', 'Could not record description.', 503)
    }
    return jsonResponse(HAPPY_DESCRIPTION_RESULT)
}

// ── Named scenario exports (convenience) ─────────────────────────────────────

export const photoAnalysisHappyFetch   = createPhotoAnalysisFetch({ scenario: 'happy' })
export const photoAnalysisDegradedFetch = createPhotoAnalysisFetch({ scenario: 'degraded' })
export const photoAnalysisCappedFetch   = createPhotoAnalysisFetch({ scenario: 'capped' })
export const photoAnalysisDisabledFetch = createPhotoAnalysisFetch({ scenario: 'disabled' })
