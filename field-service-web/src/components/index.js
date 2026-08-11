/**
 * @fileoverview Single barrel export for the shared component primitive library.
 * This is the only public entry point. Do not import from sub-paths.
 */

export { Button } from './Button/Button.jsx'
export { PageHeader } from './PageHeader/PageHeader.jsx'
export { KpiCard } from './KpiCard/KpiCard.jsx'
export { DataTable, DensityToggle } from './DataTable/DataTable.jsx'
export { useResponsiveTableMode } from './DataTable/useResponsiveTableMode.js'
export { DetailDrawer } from './DetailDrawer/DetailDrawer.jsx'
export { Modal } from './Modal/Modal.jsx'
export { FormField } from './FormField/FormField.jsx'
export { Chip } from './Chip/Chip.jsx'
export { ScorePresentation } from './ScorePresentation/ScorePresentation.jsx'
export { ToastProvider, useToast, ToastContext } from './Toast/ToastProvider.jsx'
export {
  StateSurface,
  EmptyState,
  LoadingState,
  DegradedState,
  PermissionDeniedState,
  ErrorState,
} from './StateSurface/StateSurface.jsx'
