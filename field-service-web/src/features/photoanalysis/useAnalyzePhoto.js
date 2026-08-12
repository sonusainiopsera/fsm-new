/**
 * @fileoverview useAnalyzePhoto — TanStack Query mutation for AI photo analysis (WO-181).
 *
 * Security notes (from WO-181 acceptance criteria):
 *   - The hook NEVER submits a URL from the client; the server generates the presigned GET URL.
 *   - The suggestedDescription is always pre-fill only; it is never auto-submitted.
 *   - Model output is rendered as plain text, never via dangerouslySetInnerHTML.
 *   - The advisory flag returned by the server is forwarded to the caller unchanged.
 */

import { useMutation } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { PhotoAnalysisState } from './photoAnalysisStates.js'

const ANALYSIS_PATH  = (workOrderId, photoId) =>
    `/api/v1/work-orders/${workOrderId}/photos/${photoId}/analysis`

const DESCRIPTION_PATH = (workOrderId, photoId) =>
    `/api/v1/work-orders/${workOrderId}/photos/${photoId}/description`

/**
 * @typedef {{
 *   interactionId: string,
 *   suggestedDescription: string,
 *   source: string,
 *   advisory: boolean,
 *   provider: string,
 * }} AnalysisDraft
 */

/**
 * @typedef {{
 *   state: import('./photoAnalysisStates.js').PhotoAnalysisState,
 *   draft: AnalysisDraft | null,
 *   errorCode: string | null,
 *   analyze: (opts?: { faultContext?: string }) => void,
 *   recordDescription: (description: string) => void,
 *   dismiss: () => void,
 * }} UseAnalyzePhotoResult
 */

/**
 * Hook encapsulating the photo-analysis request/response lifecycle.
 *
 * @param {string} workOrderId
 * @param {string} photoId
 * @param {{ enabled?: boolean }} [opts]   pass enabled=false when the feature flag is off
 * @returns {UseAnalyzePhotoResult}
 */
export function useAnalyzePhoto(workOrderId, photoId, { enabled = true } = {}) {
    const [state, setState] = useState(
        enabled ? PhotoAnalysisState.IDLE : PhotoAnalysisState.DISABLED
    )
    const [draft, setDraft]         = useState(null)
    const [errorCode, setErrorCode] = useState(null)

    const analysisMutation = useMutation({
        mutationFn: async ({ faultContext }) => {
            const body = { includeFaultContext: !!faultContext, faultContext: faultContext ?? null }
            const res = await fetch(ANALYSIS_PATH(workOrderId, photoId), {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify(body),
                credentials: 'include',
            })
            if (!res.ok) {
                const json = await res.json().catch(() => ({}))
                const err  = new Error(json.message ?? 'Analysis failed')
                err.code   = json.code ?? 'ANALYSIS_FAILED'
                err.status = res.status
                throw err
            }
            return res.json()
        },
        onMutate: () => {
            setState(PhotoAnalysisState.LOADING)
            setDraft(null)
            setErrorCode(null)
        },
        onSuccess: (data) => {
            setDraft(data)
            setState(PhotoAnalysisState.READY)
        },
        onError: (err) => {
            setErrorCode(err.code ?? 'ANALYSIS_FAILED')
            setState(PhotoAnalysisState.DEGRADED)
        },
    })

    const descriptionMutation = useMutation({
        mutationFn: async ({ description, suggestionInteractionId }) => {
            const res = await fetch(DESCRIPTION_PATH(workOrderId, photoId), {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify({ description, suggestionInteractionId }),
                credentials: 'include',
            })
            if (!res.ok) {
                const json = await res.json().catch(() => ({}))
                const err  = new Error(json.message ?? 'Record description failed')
                err.code   = json.code ?? 'RECORD_FAILED'
                throw err
            }
            return res.json()
        },
    })

    const analyze = useCallback(({ faultContext } = {}) => {
        if (!enabled) return
        analysisMutation.mutate({ faultContext })
    }, [analysisMutation, enabled])

    const recordDescription = useCallback((description) => {
        descriptionMutation.mutate({
            description,
            suggestionInteractionId: draft?.interactionId ?? null,
        })
        setState(PhotoAnalysisState.IDLE)
        setDraft(null)
    }, [descriptionMutation, draft])

    const dismiss = useCallback(() => {
        setState(PhotoAnalysisState.IDLE)
        setDraft(null)
        setErrorCode(null)
    }, [])

    return { state, draft, errorCode, analyze, recordDescription, dismiss }
}
