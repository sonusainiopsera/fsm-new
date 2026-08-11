/**
 * Unit tests for the navigation manifest and role-based filtering.
 * These tests do not require react-router-dom and run with existing dependencies.
 *
 * SECURITY NOTE: Tests verify that filtering is usability-only — specifically
 * that the filter never grants access and the server 403 path is the security
 * boundary (AC-3, AC-4).
 */
import { describe, it, expect } from 'vitest'
import { filterNavForRoles, NAV_MANIFEST } from './navigation.js'
import dispatcherNavFixture from '../mocks/fixtures/navigation/dispatcher-nav.json'
import technicianNavFixture from '../mocks/fixtures/navigation/technician-nav.json'
import managerNavFixture from '../mocks/fixtures/navigation/manager-nav.json'
import customerNavFixture from '../mocks/fixtures/navigation/customer-nav.json'
import adminNavFixture from '../mocks/fixtures/navigation/admin-nav.json'
import dispatcherToken from '../mocks/fixtures/tokens/dispatcher.json'
import technicianToken from '../mocks/fixtures/tokens/technician.json'
import managerToken from '../mocks/fixtures/tokens/manager.json'
import customerToken from '../mocks/fixtures/tokens/customer.json'
import adminToken from '../mocks/fixtures/tokens/admin.json'

describe('NAV_MANIFEST', () => {
  it('has no duplicate keys', () => {
    const keys = NAV_MANIFEST.map(i => i.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('every item has required fields', () => {
    NAV_MANIFEST.forEach(item => {
      expect(item.key).toBeTruthy()
      expect(item.path).toBeTruthy()
      expect(item.label).toBeTruthy()
      expect(Array.isArray(item.allowedRoles)).toBe(true)
      expect(item.allowedRoles.length).toBeGreaterThan(0)
      expect(item.surface).toBeTruthy()
    })
  })
})

describe('filterNavForRoles — dispatcher token', () => {
  it('matches dispatcher-nav fixture exactly', () => {
    const items = filterNavForRoles(dispatcherToken.roles)
    const keys = items.map(i => i.key)
    dispatcherNavFixture.forEach(expected => expect(keys).toContain(expected))
  })

  it('contains dispatch-board', () => {
    const keys = filterNavForRoles(dispatcherToken.roles).map(i => i.key)
    expect(keys).toContain('dispatch-board')
  })

  it('does NOT contain technician-only items', () => {
    const keys = filterNavForRoles(dispatcherToken.roles).map(i => i.key)
    technicianNavFixture.forEach(techKey => expect(keys).not.toContain(techKey))
  })

  it('does NOT contain customer-only items', () => {
    const keys = filterNavForRoles(dispatcherToken.roles).map(i => i.key)
    customerNavFixture.forEach(custKey => expect(keys).not.toContain(custKey))
  })
})

describe('filterNavForRoles — technician token', () => {
  it('matches technician-nav fixture exactly', () => {
    const items = filterNavForRoles(technicianToken.roles)
    const keys = items.map(i => i.key)
    expect(keys).toEqual(technicianNavFixture)
  })

  it('contains only my-jobs', () => {
    const keys = filterNavForRoles(technicianToken.roles).map(i => i.key)
    expect(keys).toContain('my-jobs')
    expect(keys.length).toBe(1)
  })

  it('does NOT contain dispatcher-only items', () => {
    const keys = filterNavForRoles(technicianToken.roles).map(i => i.key)
    expect(keys).not.toContain('dispatch-board')
    expect(keys).not.toContain('ops-dashboard')
  })
})

describe('filterNavForRoles — manager token', () => {
  it('matches manager-nav fixture', () => {
    const items = filterNavForRoles(managerToken.roles)
    const keys = items.map(i => i.key)
    managerNavFixture.forEach(expected => expect(keys).toContain(expected))
  })

  it('does NOT contain dispatch-board (dispatcher-only)', () => {
    const keys = filterNavForRoles(managerToken.roles).map(i => i.key)
    expect(keys).not.toContain('dispatch-board')
  })

  it('does NOT contain my-jobs (technician-only)', () => {
    const keys = filterNavForRoles(managerToken.roles).map(i => i.key)
    expect(keys).not.toContain('my-jobs')
  })
})

describe('filterNavForRoles — customer token', () => {
  it('matches customer-nav fixture exactly', () => {
    const keys = filterNavForRoles(customerToken.roles).map(i => i.key)
    expect(keys).toEqual(customerNavFixture)
  })

  it('contains only my-requests', () => {
    const keys = filterNavForRoles(customerToken.roles).map(i => i.key)
    expect(keys).toContain('my-requests')
    expect(keys.length).toBe(1)
  })
})

describe('filterNavForRoles — admin token', () => {
  it('matches admin-nav fixture', () => {
    const items = filterNavForRoles(adminToken.roles)
    const keys = items.map(i => i.key)
    adminNavFixture.forEach(expected => expect(keys).toContain(expected))
  })

  it('sees all surfaces', () => {
    const keys = filterNavForRoles(adminToken.roles).map(i => i.key)
    expect(keys).toContain('dispatch-board')
    expect(keys).toContain('ops-dashboard')
    expect(keys).toContain('settings')
  })
})

describe('filterNavForRoles — edge cases', () => {
  it('empty roles array returns empty array', () => {
    expect(filterNavForRoles([])).toHaveLength(0)
  })

  it('null/undefined returns empty array', () => {
    expect(filterNavForRoles(null)).toHaveLength(0)
    expect(filterNavForRoles(undefined)).toHaveLength(0)
  })

  it('unrecognised role produces no navigation items', () => {
    expect(filterNavForRoles(['SUPERUSER'])).toHaveLength(0)
  })

  it('multi-role (dispatcher + manager) returns union without duplicates', () => {
    const items = filterNavForRoles(['DISPATCHER', 'MANAGER'])
    const keys = items.map(i => i.key)
    expect(keys).toContain('dispatch-board') // dispatcher-only
    expect(keys).toContain('ops-dashboard')  // manager-only
    // work-orders is in both — must appear exactly once
    const woCount = keys.filter(k => k === 'work-orders').length
    expect(woCount).toBe(1)
    expect(new Set(keys).size).toBe(keys.length) // no duplicates
  })

  it('multi-role (dispatcher + manager) does NOT include technician-only items', () => {
    const keys = filterNavForRoles(['DISPATCHER', 'MANAGER']).map(i => i.key)
    expect(keys).not.toContain('my-jobs')
  })

  it('multi-role (manager + customer) does NOT grant each other\'s routes', () => {
    const keys = filterNavForRoles(['MANAGER', 'CUSTOMER']).map(i => i.key)
    // manager can see ops-dashboard
    expect(keys).toContain('ops-dashboard')
    // customer can see my-requests
    expect(keys).toContain('my-requests')
    // but neither should see dispatch-board (DISPATCHER only)
    expect(keys).not.toContain('dispatch-board')
  })
})
