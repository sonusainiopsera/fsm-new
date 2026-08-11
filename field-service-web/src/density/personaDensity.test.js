import { describe, it, expect } from 'vitest'
import {
  TECHNICIAN_PRESET,
  DISPATCHER_PRESET,
  OPERATIONS_PRESET,
  CUSTOMER_PRESET,
  PERSONA_PRESETS,
  resolvePersonaDensity,
  resolveMinTouchTargetPx,
  resolveRowHeightPx,
} from './personaDensity.js'

// BR-35: Technician must be verified before Dispatcher.
describe('Technician preset (BR-35 field-first)', () => {
  it('has minTouchTarget of 44px', () => {
    expect(resolveMinTouchTargetPx('technician')).toBe(44)
  })

  it('has rowHeight of 44px', () => {
    expect(resolveRowHeightPx('technician')).toBe(44)
  })

  it('has single-column layout at all breakpoints', () => {
    expect(TECHNICIAN_PRESET.columnCount).toEqual({ mobile: 1, tablet: 1, desktop: 1 })
  })

  it('places primary action at bottom (within thumb reach)', () => {
    expect(TECHNICIAN_PRESET.primaryActionPlacement).toBe('bottom')
  })

  it('uses system font stack for fastest first paint', () => {
    expect(TECHNICIAN_PRESET.fontFamily).toBe('var(--token-family-base)')
  })

  it('requires 7:1 body contrast ratio in light (raised from 4.5:1)', () => {
    expect(TECHNICIAN_PRESET.contrastBodyTextMinRatio).toBe(7)
  })

  it('overrides reference token values only (no raw literals)', () => {
    const preset = TECHNICIAN_PRESET
    expect(preset.rowHeight).toMatch(/^var\(|^calc\(/)
    expect(preset.controlHeight).toMatch(/^var\(|^calc\(/)
    expect(preset.minTouchTarget).toMatch(/^var\(|^calc\(/)
    expect(preset.cardPaddingStep).toMatch(/^var\(/)
    expect(preset.baseFontSize).toMatch(/^var\(/)
    expect(preset.fontFamily).toMatch(/^var\(/)
  })
})

describe('Dispatcher preset', () => {
  it('has rowHeight of 32px (highest density compact table rows)', () => {
    expect(resolveRowHeightPx('dispatcher')).toBe(32)
  })

  it('has minTouchTarget of 32px', () => {
    expect(resolveMinTouchTargetPx('dispatcher')).toBe(32)
  })

  it('uses drawer detail pattern', () => {
    expect(DISPATCHER_PRESET.detailPattern).toBe('drawer')
  })

  it('is keyboard-first', () => {
    expect(DISPATCHER_PRESET.keyboardFirst).toBe(true)
  })

  it('has multi-column layout (3 columns at desktop)', () => {
    expect(DISPATCHER_PRESET.columnCount.desktop).toBe(3)
  })

  it('overrides reference token values only (no raw literals)', () => {
    const preset = DISPATCHER_PRESET
    expect(preset.rowHeight).toMatch(/^var\(|^calc\(/)
    expect(preset.controlHeight).toMatch(/^var\(|^calc\(/)
  })
})

describe('Operations preset', () => {
  it('has rowHeight of 56px (generous KPI cards)', () => {
    expect(resolveRowHeightPx('operations')).toBe(56)
  })

  it('has minTouchTarget of 40px', () => {
    expect(resolveMinTouchTargetPx('operations')).toBe(40)
  })

  it('is chart-forward', () => {
    expect(OPERATIONS_PRESET.chartForward).toBe(true)
  })

  it('has 4-column desktop layout for chart-forward density', () => {
    expect(OPERATIONS_PRESET.columnCount.desktop).toBe(4)
  })
})

describe('Customer preset', () => {
  it('has rowHeight of 72px (most spacious)', () => {
    expect(resolveRowHeightPx('customer')).toBe(72)
  })

  it('has minTouchTarget of 48px (largest touch target)', () => {
    expect(resolveMinTouchTargetPx('customer')).toBe(48)
  })

  it('is plain-language', () => {
    expect(CUSTOMER_PRESET.plainLanguage).toBe(true)
  })

  it('has 2-column desktop layout (spacious)', () => {
    expect(CUSTOMER_PRESET.columnCount.desktop).toBe(2)
  })
})

describe('resolvePersonaDensity', () => {
  it('returns technician preset for "technician"', () => {
    expect(resolvePersonaDensity('technician')).toBe(TECHNICIAN_PRESET)
  })

  it('returns dispatcher preset for "dispatcher"', () => {
    expect(resolvePersonaDensity('dispatcher')).toBe(DISPATCHER_PRESET)
  })

  it('returns operations preset for "operations"', () => {
    expect(resolvePersonaDensity('operations')).toBe(OPERATIONS_PRESET)
  })

  it('returns customer preset for "customer"', () => {
    expect(resolvePersonaDensity('customer')).toBe(CUSTOMER_PRESET)
  })

  it('returns null for unknown persona', () => {
    expect(resolvePersonaDensity('unknown')).toBeNull()
  })

  it('returns null for null input', () => {
    expect(resolvePersonaDensity(null)).toBeNull()
  })

  it('returns null for undefined input', () => {
    expect(resolvePersonaDensity(undefined)).toBeNull()
  })
})

describe('Persona variant matrix — columnCount at target viewport', () => {
  const cases = [
    { persona: 'technician', viewport: 'mobile', expected: 1 },
    { persona: 'technician', viewport: 'tablet', expected: 1 },
    { persona: 'technician', viewport: 'desktop', expected: 1 },
    { persona: 'dispatcher', viewport: 'mobile', expected: 1 },
    { persona: 'dispatcher', viewport: 'tablet', expected: 2 },
    { persona: 'dispatcher', viewport: 'desktop', expected: 3 },
    { persona: 'operations', viewport: 'mobile', expected: 1 },
    { persona: 'operations', viewport: 'tablet', expected: 2 },
    { persona: 'operations', viewport: 'desktop', expected: 4 },
    { persona: 'customer', viewport: 'mobile', expected: 1 },
    { persona: 'customer', viewport: 'tablet', expected: 1 },
    { persona: 'customer', viewport: 'desktop', expected: 2 },
  ]

  for (const { persona, viewport, expected } of cases) {
    it(`${persona} at ${viewport} has ${expected} column(s)`, () => {
      const preset = resolvePersonaDensity(persona)
      expect(preset.columnCount[viewport]).toBe(expected)
    })
  }
})

describe('PERSONA_PRESETS index', () => {
  it('contains all four personas', () => {
    expect(Object.keys(PERSONA_PRESETS).sort()).toEqual(['customer', 'dispatcher', 'operations', 'technician'])
  })
})
