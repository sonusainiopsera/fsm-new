import React, { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';

import { Modal } from '../Modal/Modal.jsx';

function TestModal({ initialOpen = true }) {
  const [open, setOpen] = useState(initialOpen);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} id="trigger">Open</button>
      <Modal open={open} title="Test Modal" onClose={() => setOpen(false)}>
        <button type="button">First focusable</button>
        <button type="button">Last focusable</button>
      </Modal>
    </>
  );
}

describe('Modal', () => {
  it('renders when open', () => {
    render(<TestModal />);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Test Modal')).toBeInTheDocument();
  });

  it('is not rendered when closed', () => {
    render(<TestModal initialOpen={false} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('has aria-modal and aria-labelledby', () => {
    render(<TestModal />);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog.getAttribute('aria-labelledby')).toBeTruthy();
    const titleId = dialog.getAttribute('aria-labelledby');
    const titleEl = document.getElementById(titleId);
    expect(titleEl?.textContent).toBe('Test Modal');
  });

  it('closes on Escape key', async () => {
    render(<TestModal />);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes when clicking close button', async () => {
    render(<TestModal />);
    await userEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes when clicking outside the panel (scrim click)', async () => {
    render(<TestModal />);
    const dialog = screen.getByRole('dialog');
    const scrim = dialog.parentElement;
    await userEvent.click(scrim);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('renders footer when provided', () => {
    render(
      <Modal open title="T" onClose={() => {}} footer={<button type="button">Confirm</button>}>
        Body
      </Modal>
    );
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
  });
});
