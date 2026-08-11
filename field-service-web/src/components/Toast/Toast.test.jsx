import { render, screen, fireEvent, act } from '@testing-library/react'
import { ToastProvider, useToast } from './ToastProvider.jsx'

function ToastTrigger({ variant = 'info', message = 'Test message' }) {
  const { toast } = useToast()
  return <button onClick={() => toast(message, variant)}>trigger</button>
}

function setup(variant) {
  render(
    <ToastProvider>
      <ToastTrigger variant={variant} />
    </ToastProvider>
  )
  return screen.getByRole('button', { name: 'trigger' })
}

describe('ToastProvider', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('renders info toast in polite aria-live region', () => {
    const trigger = setup('info')
    fireEvent.click(trigger)
    const region = document.querySelector('[aria-live="polite"]')
    expect(region).toBeInTheDocument()
    expect(region.textContent).toContain('Test message')
  })

  it('renders danger toast in assertive aria-live region', () => {
    const trigger = setup('danger')
    fireEvent.click(trigger)
    const region = document.querySelector('[aria-live="assertive"]')
    expect(region).toBeInTheDocument()
    expect(region.textContent).toContain('Test message')
  })

  it('auto-dismisses non-danger toast after timeout', () => {
    const trigger = setup('info')
    fireEvent.click(trigger)
    expect(document.querySelector('[aria-live="polite"]').textContent).toContain('Test message')
    act(() => vi.runAllTimers())
    expect(document.querySelector('[aria-live="polite"]').textContent).not.toContain('Test message')
  })

  it('does not auto-dismiss danger toast', () => {
    const trigger = setup('danger')
    fireEvent.click(trigger)
    act(() => vi.runAllTimers())
    const region = document.querySelector('[aria-live="assertive"]')
    expect(region.textContent).toContain('Test message')
  })

  it('throttles to at most 1 non-danger toast visible', () => {
    render(
      <ToastProvider>
        <ToastTrigger variant="success" message="First" />
      </ToastProvider>
    )
    const trigger = screen.getByRole('button')
    fireEvent.click(trigger)
    fireEvent.click(trigger)
    const region = document.querySelector('[aria-live="polite"]')
    const toasts = region.querySelectorAll('[data-toast]')
    expect(toasts.length).toBeLessThanOrEqual(1)
  })
})
