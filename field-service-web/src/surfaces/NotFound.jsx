/**
 * @fileoverview 404 Not Found surface — catch-all route.
 */
import { PermissionDeniedState } from '../components/index.js'

export default function NotFound() {
  return <PermissionDeniedState message="The page you requested does not exist or you do not have permission to view it." />
}
