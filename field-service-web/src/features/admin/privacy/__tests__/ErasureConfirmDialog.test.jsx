/**
 * @fileoverview RTL tests for ErasureConfirmDialog.
 *
 * Covers: submit disabled until "CONFIRM_ERASURE" typed, irreversibility warning.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import { ErasureConfirmDialog } from '../ErasureConfirmDialog.jsx'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['PRIVACY_ADMIN']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

const defaultProps = {
  open: true,
  onClose: vi.fn(),
  subjectType: 'CUSTOMER',
  subjectId: 'cust-222',
  dsarRequestId: 'd1000000-0000-7000-8000-000000000002',
  onConfirm: vi.fn(),
  isPending: false,
  errorMessage: null,
}

function renderDialog(props = {}) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth()}>
        <ErasureConfirmDialog {...defaultProps} {...props} />
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ErasureConfirmDialog — confirmation gate', () => {
  it('submit is disabled until CONFIRM_ERASURE is typed', async () => {
    renderDialog()
    const submitBtn = screen.getByRole('button', { name: /confirm erasure/i })
    expect(submitBtn).toBeDisabled()
  })

  it('submit remains disabled with partial text', async () => {
    renderDialog()
    const input = screen.getByRole('textbox', { name: /type.*confirm/i })
    fireEvent.change(input, { target: { value: 'CONFIRM' } })
    const submitBtn = screen.getByRole('button', { name: /confirm erasure/i })
    expect(submitBtn).toBeDisabled()
  })

  it('submit is enabled after typing exact phrase', async () => {
    renderDialog()
    const input = screen.getByRole('textbox', { name: /type.*confirm/i })
    fireEvent.change(input, { target: { value: 'CONFIRM_ERASURE' } })
    const submitBtn = screen.getByRole('button', { name: /confirm erasure/i })
    expect(submitBtn).toBeEnabled()
  })

  it('calls onConfirm when submitted with correct phrase', async () => {
    const onConfirm = vi.fn()
    renderDialog({ onConfirm })
    const input = screen.getByRole('textbox', { name: /type.*confirm/i })
    fireEvent.change(input, { target: { value: 'CONFIRM_ERASURE' } })
    const submitBtn = screen.getByRole('button', { name: /confirm erasure/i })
    fireEvent.click(submitBtn)
    expect(onConfirm).toHaveBeenCalledOnce()
  })
})

describe('ErasureConfirmDialog — warning text', () => {
  it('states action is irreversible', async () => {
    renderDialog()
    expect(screen.getByText(/irreversible/i)).toBeInTheDocument()
  })

  it('mentions non-identifying data retention', async () => {
    renderDialog()
    expect(screen.getByText(/non-identifying/i)).toBeInTheDocument()
  })
})

describe('ErasureConfirmDialog — error state', () => {
  it('displays errorMessage when provided', async () => {
    renderDialog({ errorMessage: 'Identity verification required.' })
    await waitFor(() =>
      screen.getByText(/Identity verification required/i)
    )
  })
})

describe('ErasureConfirmDialog — not shown when closed', () => {
  it('renders nothing when open=false', () => {
    renderDialog({ open: false })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
