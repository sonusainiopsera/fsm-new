/**
 * @fileoverview JobDetailScreen — technician job detail screen (WO-156).
 * Delegates to JobDetailView which assembles the full enriched projection.
 */
import { useParams } from 'react-router-dom'
import { JobDetailView } from '../../../app/technician/components/JobDetailView.jsx'
import { EmptyState } from '../../../components/index.js'

export default function JobDetailScreen() {
  const { workOrderId } = useParams()

  if (!workOrderId) {
    return <EmptyState message="No job selected." />
  }

  return <JobDetailView workOrderId={workOrderId} />
}
