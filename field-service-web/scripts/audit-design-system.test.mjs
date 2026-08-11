/**
 * Unit tests for the design-system audit metric calculations.
 * These run via Vitest (picked up because they import Vitest globals).
 */
import { describe, it, expect } from 'vitest'
import { computeMetrics, analyzeCss, analyzeJsx } from './audit-design-system.mjs'

describe('computeMetrics', () => {
  it('100% adoption when all declarations reference tokens', () => {
    const { adoptionPct, bespokePct } = computeMetrics({ totalDeclarations: 10, tokenDeclarations: 10 })
    expect(adoptionPct).toBe(100)
    expect(bespokePct).toBe(0)
  })

  it('0% adoption when no declarations reference tokens', () => {
    const { adoptionPct, bespokePct } = computeMetrics({ totalDeclarations: 10, tokenDeclarations: 0 })
    expect(adoptionPct).toBe(0)
    expect(bespokePct).toBe(100)
  })

  it('95% adoption for 19/20 token declarations', () => {
    const { adoptionPct } = computeMetrics({ totalDeclarations: 20, tokenDeclarations: 19 })
    expect(adoptionPct).toBe(95)
  })

  it('handles zero total declarations (avoids division by zero)', () => {
    const { adoptionPct, bespokePct } = computeMetrics({ totalDeclarations: 0, tokenDeclarations: 0 })
    expect(adoptionPct).toBe(100)
    expect(bespokePct).toBe(0)
  })
})

describe('analyzeCss — token referencing', () => {
  it('counts token-referencing declarations', () => {
    const css = `
      .button { color: var(--token-text-primary); background-color: var(--token-accent-600); }
    `
    const { total, tokenReferencing } = analyzeCss(css)
    expect(total).toBe(2)
    expect(tokenReferencing).toBe(2)
  })

  it('counts hard-coded colour literals as non-token', () => {
    const css = `.bad { color: #ff0000; }`
    const { total, tokenReferencing, hardcoded } = analyzeCss(css)
    expect(total).toBe(1)
    expect(tokenReferencing).toBe(0)
    expect(hardcoded).toContain('color: #ff0000')
  })

  it('counts hard-coded pixel values in border-radius as bespoke', () => {
    const css = `.bad { border-radius: 8px; }`
    const { hardcoded } = analyzeCss(css)
    expect(hardcoded.length).toBeGreaterThan(0)
  })

  it('does not flag var(--token-*) expressions', () => {
    const css = `.ok { border-radius: var(--token-radius-card); color: var(--token-text-primary); }`
    const { hardcoded } = analyzeCss(css)
    expect(hardcoded).toHaveLength(0)
  })

  it('does not flag inherit/currentColor/transparent', () => {
    const css = `.ok { color: inherit; background: transparent; }`
    const { tokenReferencing } = analyzeCss(css)
    expect(tokenReferencing).toBe(2)
  })
})

describe('analyzeJsx — inline style literals', () => {
  it('detects hard-coded hex colours in style prop', () => {
    const jsx = `<div style={{ color: '#ff0000', fontSize: '14px' }} />`
    const { inlineStyleLiterals } = analyzeJsx(jsx)
    expect(inlineStyleLiterals.length).toBeGreaterThan(0)
  })

  it('does not flag token references in style prop', () => {
    const jsx = `<div style={{ color: 'var(--token-text-primary)' }} />`
    const { inlineStyleLiterals } = analyzeJsx(jsx)
    expect(inlineStyleLiterals).toHaveLength(0)
  })

  it('does not flag non-style props', () => {
    const jsx = `<button onClick={() => {}} className="btn-primary">Click</button>`
    const { inlineStyleLiterals } = analyzeJsx(jsx)
    expect(inlineStyleLiterals).toHaveLength(0)
  })
})
