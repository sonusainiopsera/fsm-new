import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { DensityProvider } from '../../density/DensityContext.js';
import { FormField } from '../FormField/FormField.jsx';

function renderField(props) {
  return render(
    <DensityProvider>
      <FormField {...props}>
        <input type="text" />
      </FormField>
    </DensityProvider>
  );
}

describe('FormField', () => {
  it('renders label', () => {
    renderField({ label: 'Customer Name' });
    expect(screen.getByText('Customer Name')).toBeInTheDocument();
  });

  it('renders required indicator', () => {
    renderField({ label: 'Email', required: true });
    expect(screen.getByTitle('Required')).toBeInTheDocument();
  });

  it('renders help text', () => {
    renderField({ label: 'Email', help: 'Enter your work email' });
    expect(screen.getByText('Enter your work email')).toBeInTheDocument();
  });

  it('wires aria-invalid on child input when errors present', () => {
    renderField({ label: 'Email', errors: [{ field: 'email', message: 'Required' }] });
    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('does not set aria-invalid when no errors', () => {
    renderField({ label: 'Email' });
    const input = screen.getByRole('textbox');
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('renders all server-side field errors', () => {
    renderField({ label: 'Email', errors: [
      { field: 'email', message: 'Required field' },
      { field: 'email', message: 'Invalid format' },
    ]});
    expect(screen.getByText('Required field')).toBeInTheDocument();
    expect(screen.getByText('Invalid format')).toBeInTheDocument();
  });

  it('wires aria-describedby for help text and errors', () => {
    renderField({ label: 'Email', help: 'Hint', errors: ['Error one'] });
    const input = screen.getByRole('textbox');
    const describedBy = input.getAttribute('aria-describedby') ?? '';
    expect(describedBy.split(' ').length).toBeGreaterThanOrEqual(2);
  });

  it('also accepts string errors', () => {
    renderField({ label: 'Name', errors: ['Too short', 'Must be unique'] });
    expect(screen.getByText('Too short')).toBeInTheDocument();
    expect(screen.getByText('Must be unique')).toBeInTheDocument();
  });
});
