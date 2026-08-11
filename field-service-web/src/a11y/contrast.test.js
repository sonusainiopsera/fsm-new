import { describe, it, expect } from 'vitest'
import {
  parseHex,
  relativeLuminance,
  computeContrastRatio,
  resolveTokenColor,
  checkPairings,
  assertPairings,
  LIGHT_TOKEN_VALUES,
  DARK_TOKEN_VALUES,
} from './contrast.js'
import pairings from './contrastPairings.json'

describe('parseHex', () => {
  it('parses 6-char hex correctly', () => {
    expect(parseHex('#ffffff')).toEqual([255, 255, 255])
    expect(parseHex('#000000')).toEqual([0, 0, 0])
    expect(parseHex('#111827')).toEqual([17, 24, 39])
  })

  it('parses 3-char hex by doubling digits', () => {
    expect(parseHex('#fff')).toEqual([255, 255, 255])
    expect(parseHex('#000')).toEqual([0, 0, 0])
  })
})

describe('relativeLuminance', () => {
  it('white has luminance 1', () => {
    expect(relativeLuminance([255, 255, 255])).toBeCloseTo(1, 3)
  })

  it('black has luminance 0', () => {
    expect(relativeLuminance([0, 0, 0])).toBeCloseTo(0, 5)
  })
})

describe('computeContrastRatio', () => {
  it('black on white = 21:1', () => {
    expect(computeContrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 0)
  })

  it('white on white = 1:1', () => {
    expect(computeContrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 3)
  })

  it('is symmetric (fg/bg order does not matter)', () => {
    const r1 = computeContrastRatio('#111827', '#f9fafb')
    const r2 = computeContrastRatio('#f9fafb', '#111827')
    expect(r1).toBeCloseTo(r2, 5)
  })
})

describe('contrast pairings — light appearance', () => {
  it('all declared pairings pass WCAG AA in light', () => {
    expect(() => assertPairings(pairings, LIGHT_TOKEN_VALUES, 'light')).not.toThrow()
  })

  it('text-primary on surface-page in light exceeds 4.5:1', () => {
    const ratio = computeContrastRatio(
      LIGHT_TOKEN_VALUES['--token-text-primary'],
      LIGHT_TOKEN_VALUES['--token-surface-page'],
    )
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })

  it('technician body text (text-primary on surface-page in light) exceeds 7:1', () => {
    const ratio = computeContrastRatio(
      LIGHT_TOKEN_VALUES['--token-text-primary'],
      LIGHT_TOKEN_VALUES['--token-surface-page'],
    )
    expect(ratio).toBeGreaterThanOrEqual(7)
  })

  it('text-secondary on surface-page in light passes 4.5:1', () => {
    const ratio = computeContrastRatio(
      LIGHT_TOKEN_VALUES['--token-text-secondary'],
      LIGHT_TOKEN_VALUES['--token-surface-page'],
    )
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })
})

describe('contrast pairings — dark appearance', () => {
  it('all declared pairings pass WCAG AA in dark', () => {
    expect(() => assertPairings(pairings, DARK_TOKEN_VALUES, 'dark')).not.toThrow()
  })

  it('text-primary on surface-page in dark exceeds 4.5:1', () => {
    const ratio = computeContrastRatio(
      DARK_TOKEN_VALUES['--token-text-primary'],
      DARK_TOKEN_VALUES['--token-surface-page'],
    )
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })
})

describe('technician persona pairings', () => {
  it('technician-specific pairings pass in both appearances', () => {
    expect(() => assertPairings(pairings, LIGHT_TOKEN_VALUES, 'light', 'technician')).not.toThrow()
    expect(() => assertPairings(pairings, DARK_TOKEN_VALUES, 'dark', 'technician')).not.toThrow()
  })
})

describe('resolveTokenColor', () => {
  it('resolves a known token name', () => {
    expect(resolveTokenColor('--token-text-primary', LIGHT_TOKEN_VALUES)).toBe('#111827')
  })

  it('throws for an unknown token name (undeclared pairing = build failure)', () => {
    expect(() => resolveTokenColor('--token-unknown-xyz', LIGHT_TOKEN_VALUES)).toThrow(
      /undeclared|not in the resolved value set/i
    )
  })
})

describe('checkPairings — edge cases', () => {
  it('returns empty failures when all pass', () => {
    const { failures } = checkPairings(pairings, LIGHT_TOKEN_VALUES, 'light')
    expect(failures).toHaveLength(0)
  })

  it('returns failures when a pairing fails', () => {
    const badManifest = {
      pairings: [{
        name: 'intentionally-bad',
        fg: '--token-text-disabled',
        bg: '--token-surface-page',
        minRatio: 21,
        personas: ['dispatcher'],
        appearances: ['light'],
      }],
    }
    const { failures } = checkPairings(badManifest, LIGHT_TOKEN_VALUES, 'light')
    expect(failures.length).toBeGreaterThan(0)
    expect(failures[0].name).toBe('intentionally-bad')
  })

  it('skips pairings that do not match the requested appearance', () => {
    const lightOnlyManifest = {
      pairings: [{
        name: 'light-only',
        fg: '--token-text-primary',
        bg: '--token-surface-page',
        minRatio: 4.5,
        personas: ['dispatcher'],
        appearances: ['light'],
      }],
    }
    const { failures } = checkPairings(lightOnlyManifest, DARK_TOKEN_VALUES, 'dark')
    expect(failures).toHaveLength(0)
  })
})
