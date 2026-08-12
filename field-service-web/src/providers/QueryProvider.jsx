import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'

/**
 * @param {{ children: React.ReactNode }} props
 */
export function QueryProvider({ children }) {
  // Use useState so the QueryClient isn't recreated on re-renders
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Stale after 30 seconds
            staleTime: 30_000,
            // Keep data in cache for 5 minutes after component unmounts
            gcTime: 5 * 60_000,
            // Retry once on failure
            retry: 1,
            retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 10_000),
            // Refetch on window focus to keep data fresh
            refetchOnWindowFocus: true,
          },
          mutations: {
            retry: 0,
          },
        },
      })
  )

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
