/**
 * Dispatcher surface — route group for /dispatch/*.
 *
 * Entry chunk for the dispatch surface.
 * Roles: DISPATCHER, ADMIN, MANAGER.
 *
 * The WorkOrderBoardPage is the primary screen: a 30-second conditional-poll
 * filterable table with a deep-linkable detail drawer.
 */

export { default } from '../../features/workorders/WorkOrderBoardPage.jsx';
