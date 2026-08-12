/**
 * @fileoverview JobsListScreen — displays the technician's assigned work orders.
 * Reads from the cached day-list query; renders a stale indicator when offline.
 * Full implementation in a downstream WO; this version renders the fixture data.
 */
import { useQuery } from '@tanstack/react-query'
import { get } from '../../../api/http.js'
import { EmptyState, LoadingState, DegradedState } from '../../../components/index.js'

const DAY_LIST_QUERY_KEY = ['technician', 'day-list']

function fetchDayList({ signal }) {
  return get('/technicians/me/work-orders', { signal })
}

/**
 * @param {{ isConnected: boolean }} props
 */
export default function JobsListScreen({ isConnected }) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: DAY_LIST_QUERY_KEY,
    queryFn: fetchDayList,
    staleTime: 30_000,
    gcTime: 12 * 60 * 60 * 1000, // 12 h — shift TTL
    retry: false,
  })

  if (isLoading) {
    return <LoadingState />
  }

  if (isError) {
    const isOfflineError = !isConnected
    return (
      <DegradedState
        message={
          isOfflineError
            ? 'Showing cached job list. Live data will update when connectivity is restored.'
            : `Unable to load jobs: ${error?.message ?? 'Server error'}`
        }
      />
    )
  }

  const jobs = data?.jobs ?? data?.content ?? []

  if (jobs.length === 0) {
    return <EmptyState message="No assigned jobs for today." />
  }

  return (
    <ul
      aria-label="Assigned jobs"
      style={{
        listStyle: 'none',
        margin: 0,
        padding: 'var(--token-space-4)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-3)',
      }}
    >
      {jobs.map((job) => (
        <li
          key={job.id}
          style={{
            padding: 'var(--token-space-4)',
            borderRadius: 'var(--token-radius-card)',
            background: 'var(--token-neutral-0, #ffffff)',
            boxShadow: 'var(--token-elevation-1)',
          }}
        >
          <div style={{ fontWeight: 600 }}>{job.title}</div>
          <div style={{ fontSize: 'var(--token-fs-13)', marginTop: 4 }}>{job.site}</div>
        </li>
      ))}
    </ul>
  )
}
