/**
 * @fileoverview LogWorkScreen — log labour time and parts for a job in progress (WO-157).
 * Fetches hold reasons from the job detail query to pass to LogWorkView.
 */
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { get } from '../../../api/http.js'
import { LogWorkView } from '../../../app/technician/components/LogWorkView.jsx'
import { EmptyState, LoadingState } from '../../../components/index.js'

export default function LogWorkScreen() {
  const { workOrderId } = useParams()

  const { data: job, isLoading } = useQuery({
    queryKey: ['technician', 'job-detail', workOrderId],
    queryFn: () => get(`/technicians/me/work-orders/${workOrderId}`),
    enabled: !!workOrderId,
    staleTime: 30_000,
  })

  if (!workOrderId) return <EmptyState message="No job selected." />
  if (isLoading) return <LoadingState />

  return (
    <LogWorkView
      workOrderId={workOrderId}
      holdReasons={job?.holdReasons ?? []}
    />
  )
}
