import { render, screen } from '@testing-library/react'
import { ScorePresentation } from './ScorePresentation.jsx'

const FACTORS = [
  { label: 'On-time', weight: 0.4, normalizedValue: 0.85 },
  { label: 'Feedback', weight: 0.3, normalizedValue: 0.72 },
]

describe('ScorePresentation', () => {
  it('renders score value', () => {
    render(<ScorePresentation score={78} maxScore={100} label="Performance" factors={FACTORS} />)
    expect(screen.getByText('78')).toBeInTheDocument()
  })

  it('renders label', () => {
    render(<ScorePresentation score={78} maxScore={100} label="Performance" factors={FACTORS} />)
    expect(screen.getByText('Performance')).toBeInTheDocument()
  })

  it('renders progressbar for overall score', () => {
    render(<ScorePresentation score={78} maxScore={100} label="Perf" factors={FACTORS} />)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('renders factor labels', () => {
    render(<ScorePresentation score={78} maxScore={100} label="Perf" factors={FACTORS} />)
    expect(screen.getByText('On-time')).toBeInTheDocument()
    expect(screen.getByText('Feedback')).toBeInTheDocument()
  })

  it('has no semantic color (BR-33 — monochrome only)', () => {
    const { container } = render(
      <ScorePresentation score={78} maxScore={100} label="Perf" factors={FACTORS} />
    )
    const allElements = container.querySelectorAll('*')
    const trafficLightColors = ['#e53e3e', '#38a169', '#d69e2e', 'red', 'green', 'yellow',
      'var(--token-danger', 'var(--token-success', 'var(--token-warning']
    allElements.forEach(el => {
      const style = el.getAttribute('style') ?? ''
      trafficLightColors.forEach(color => {
        expect(style).not.toContain(color)
      })
    })
  })
})
