import { render, screen } from '@testing-library/react'
import { FormField } from './FormField.jsx'

describe('FormField', () => {
  function InputChild(props) {
    return <input data-testid="input" {...props} />
  }

  it('renders label', () => {
    render(<FormField label="Title">{(p) => <InputChild {...p} />}</FormField>)
    expect(screen.getByText('Title')).toBeInTheDocument()
  })

  it('passes id to child via render prop', () => {
    render(<FormField label="Title">{(p) => <InputChild {...p} />}</FormField>)
    const input = screen.getByTestId('input')
    expect(input.id).toBeTruthy()
  })

  it('marks input required when required prop set', () => {
    render(<FormField label="Title" required>{(p) => <InputChild {...p} />}</FormField>)
    expect(screen.getByTestId('input')).toHaveAttribute('aria-required', 'true')
  })

  it('renders help text', () => {
    render(<FormField label="Title" helpText="Enter a short title.">{(p) => <InputChild {...p} />}</FormField>)
    expect(screen.getByText('Enter a short title.')).toBeInTheDocument()
  })

  it('renders client errors in alert region', () => {
    render(
      <FormField label="Title" errors={['Title is required', 'Too short']}>
        {(p) => <InputChild {...p} />}
      </FormField>
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('Title is required')).toBeInTheDocument()
    expect(screen.getByText('Too short')).toBeInTheDocument()
  })

  it('renders server fieldErrors', () => {
    render(
      <FormField
        label="Priority"
        fieldErrors={[{ field: 'priority', message: 'Must be critical/high/medium/low' }]}
      >
        {(p) => <InputChild {...p} />}
      </FormField>
    )
    expect(screen.getByText('Must be critical/high/medium/low')).toBeInTheDocument()
  })

  it('sets aria-invalid when errors present', () => {
    render(<FormField label="X" errors={['bad']}>{(p) => <InputChild {...p} />}</FormField>)
    expect(screen.getByTestId('input')).toHaveAttribute('aria-invalid', 'true')
  })

  it('sets aria-describedby when help text present', () => {
    render(<FormField label="X" helpText="Help">{(p) => <InputChild {...p} />}</FormField>)
    const input = screen.getByTestId('input')
    expect(input).toHaveAttribute('aria-describedby')
  })
})
