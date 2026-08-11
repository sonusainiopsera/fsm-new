import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { Button } from '../Button/Button.jsx';
import { PageHeader } from '../PageHeader/PageHeader.jsx';

describe('PageHeader', () => {
  it('renders title', () => {
    render(<PageHeader title="Work Orders" />);
    expect(screen.getByRole('heading', { level: 1, name: 'Work Orders' })).toBeInTheDocument();
  });

  it('renders breadcrumb with current page', () => {
    render(<PageHeader title="Detail" breadcrumb={[{ label: 'Home', href: '/' }, { label: 'Detail' }]} />);
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toBeInTheDocument();
    expect(screen.getByText('Detail')).toBeInTheDocument();
    const currentLink = screen.getByText('Detail');
    expect(currentLink).toHaveAttribute('aria-current', 'page');
  });

  it('renders one primary action', () => {
    render(<PageHeader title="T" primaryAction={<Button>Create</Button>} />);
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });

  it('renders secondary actions', () => {
    render(
      <PageHeader
        title="T"
        primaryAction={<Button>Primary</Button>}
        secondaryActions={[<Button key="a" variant="ghost">Export</Button>]}
      />
    );
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Primary' })).toBeInTheDocument();
  });

  it('throws in development when more than one primary action provided', () => {
    const originalEnv = process.env.NODE_ENV;
    process.env.NODE_ENV = 'development';
    expect(() =>
      render(
        <PageHeader
          title="T"
          primaryAction={[<Button key="a">A</Button>, <Button key="b">B</Button>]}
        />
      )
    ).toThrow(/exactly one primaryAction/);
    process.env.NODE_ENV = originalEnv;
  });
});
