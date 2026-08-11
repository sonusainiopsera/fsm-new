/**
 * Unit tests for eventKeyMap.js — query key invalidation mapping.
 */

import { describe, it, expect } from 'vitest'
import { getInvalidationKeys, EVENT_KEY_MAP } from './eventKeyMap.js'

describe('getInvalidationKeys', () => {
  it('maps WorkOrderAtRisk to workOrders and dashboard/atRisk keys', () => {
    const keys = getInvalidationKeys('WorkOrderAtRisk')
    expect(keys).toContainEqual(['workOrders'])
    expect(keys).toContainEqual(['dashboard', 'atRisk'])
  })

  it('maps WorkOrderBreached to workOrders and dashboard/breached keys', () => {
    const keys = getInvalidationKeys('WorkOrderBreached')
    expect(keys).toContainEqual(['workOrders'])
    expect(keys).toContainEqual(['dashboard', 'breached'])
  })

  it('maps WorkOrderStateChanged to workOrders', () => {
    const keys = getInvalidationKeys('WorkOrderStateChanged')
    expect(keys).toContainEqual(['workOrders'])
  })

  it('maps PartsConsumed to workOrders and stock', () => {
    const keys = getInvalidationKeys('PartsConsumed')
    expect(keys).toContainEqual(['workOrders'])
    expect(keys).toContainEqual(['stock'])
  })

  it('returns empty array for unknown event type', () => {
    expect(getInvalidationKeys('UnknownEvent')).toEqual([])
  })

  it('all keys in EVENT_KEY_MAP are arrays of string arrays', () => {
    for (const [, keyArrays] of Object.entries(EVENT_KEY_MAP)) {
      expect(Array.isArray(keyArrays)).toBe(true)
      for (const key of keyArrays) {
        expect(Array.isArray(key)).toBe(true)
        key.forEach(part => expect(typeof part).toBe('string'))
      }
    }
  })
})
