/**
 * @fileoverview useCopilotCapability — reads the server-reported copilot feature flag.
 *
 * Returns false (disabled) on any error so the entry point is absent rather than
 * present-and-failing when the capabilities endpoint is unavailable.
 *
 * The capabilities response shape: { copilotEnabled: boolean, ... }
 */
import { useQuery } from '@tanstack/react-query'
import { get } from '../../api/http.js'

const CAPABILITIES_KEY = ['capabilities']
const CAPABILITIES_PATH = '/capabilities'

/**
 * @returns {{ copilotEnabled: boolean, isLoading: boolean }}
 */
export function useCopilotCapability() {
  const { data, isLoading } = useQuery({
    queryKey: CAPABILITIES_KEY,
    queryFn: ({ signal }) => get(CAPABILITIES_PATH, { signal }),
    staleTime: 5 * 60_000,
    retry: false,
    // Default to disabled on any failure — never present-and-failing
    throwOnError: false,
  })

  return {
    copilotEnabled: !!(data?.copilotEnabled),
    isLoading,
  }
}
