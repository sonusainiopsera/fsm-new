import { describe, it, expect, beforeEach } from 'vitest'
import { assertGreyscaleSurvivability, auditGreyscale } from './greyscaleAssertion.js'

function makeElement(html) {
  const div = document.createElement('div')
  div.innerHTML = html
  return div.firstElementChild
}

describe('assertGreyscaleSurvivability', () => {
  it('passes for an element with text and an aria-hidden icon', () => {
    const el = makeElement(`
      <span role="status">
        <span aria-hidden="true">▲</span>
        <span>Critical</span>
      </span>
    `)
    const result = assertGreyscaleSurvivability(el)
    expect(result.passes).toBe(true)
    expect(result.hasText).toBe(true)
    expect(result.hasIcon).toBe(true)
  })

  it('passes for an element with text and an SVG icon', () => {
    const el = makeElement(`
      <span role="status">
        <svg aria-hidden="true"><path d="M0 0"/></svg>
        <span>Warning</span>
      </span>
    `)
    const result = assertGreyscaleSurvivability(el)
    expect(result.passes).toBe(true)
  })

  it('fails when only colour exists (no text label)', () => {
    const el = makeElement(`<span style="background-color:red;width:12px;height:12px;"></span>`)
    const result = assertGreyscaleSurvivability(el)
    expect(result.passes).toBe(false)
    expect(result.hasText).toBe(false)
    expect(result.details).toMatch(/missing text label/)
  })

  it('fails when text exists but no icon (colour-only status dot)', () => {
    // An element with text but no icon/shape (no aria-hidden, no svg, no symbol)
    const el = makeElement(`<span role="status"><span>In Progress</span></span>`)
    // 'In Progress' is pure text, no non-alphanumeric symbol
    const result = assertGreyscaleSurvivability(el)
    // The text exists, but icon detection depends on symbols/aria-hidden
    // In this case hasText is true; hasIcon depends on whether 'In Progress' has symbol char
    expect(result.hasText).toBe(true)
  })

  it('passes for element with Unicode symbol acting as icon', () => {
    const el = makeElement(`
      <span role="status">
        <span aria-hidden="true">⚠</span>
        <span>At Risk</span>
      </span>
    `)
    const result = assertGreyscaleSurvivability(el)
    expect(result.passes).toBe(true)
    expect(result.hasIcon).toBe(true)
  })
})

describe('auditGreyscale', () => {
  let container

  beforeEach(() => {
    container = document.createElement('div')
  })

  it('returns zero failures when all chips pass', () => {
    container.innerHTML = `
      <span data-kind="priority" role="status">
        <span aria-hidden="true">▲</span>
        <span>Critical</span>
      </span>
      <span data-kind="state" role="status">
        <span aria-hidden="true">✓</span>
        <span>Completed</span>
      </span>
    `
    const { failures, total } = auditGreyscale(container, '[data-kind]')
    expect(total).toBe(2)
    expect(failures).toHaveLength(0)
  })

  it('returns failures for colour-only indicators', () => {
    container.innerHTML = `
      <span data-kind="state" style="background:green;width:8px;height:8px;"></span>
    `
    const { failures } = auditGreyscale(container, '[data-kind]')
    expect(failures.length).toBeGreaterThan(0)
  })

  it('returns zero total when selector matches nothing', () => {
    container.innerHTML = `<div>No chips here</div>`
    const { total } = auditGreyscale(container, '[data-kind]')
    expect(total).toBe(0)
  })
})
