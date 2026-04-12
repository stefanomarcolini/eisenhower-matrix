import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { TaskCard } from '../TaskCard';
import type { Task } from '../../api/tasks';

vi.mock('../../hooks/useTasks', () => ({
  usePatchTask: () => ({
    mutate: vi.fn(),
    isPending: false,
  }),
}));

const TASK: Task = {
  id: 't-1',
  title: 'Quarterly planning',
  description: 'Review priorities',
  state: 'PLANNED',
  importance: 'HIGH',
  urgency: 'MEDIUM',
  dueDate: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
};

describe('TaskCard', () => {
  it('renders importance and urgency tags in list cards', () => {
    render(<TaskCard task={TASK} onClick={() => {}} />);

    expect(screen.getByText('Importance: HIGH')).toBeInTheDocument();
    expect(screen.getByText('Urgency: MEDIUM')).toBeInTheDocument();
  });
});

