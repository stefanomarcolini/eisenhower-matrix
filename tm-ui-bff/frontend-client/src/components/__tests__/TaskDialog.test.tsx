import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TaskDialog } from '../TaskDialog';
import type { Task } from '../../api/tasks';

// Prevent real API calls
vi.mock('../../api/tasks', () => ({
  createTask: vi.fn(),
  updateTask: vi.fn(),
  patchTask:  vi.fn(),
  deleteTask: vi.fn(),
}));

const PLANNED_TASK: Task = {
  id: 'task-abc', title: 'Fix the bug', description: 'Details here',
  state: 'PLANNED', importance: 'HIGH', urgency: 'MEDIUM',
  dueDate: '2025-12-31', version: 2,
  createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

const COMPLETED_TASK: Task = {
  ...PLANNED_TASK, id: 'task-def', state: 'COMPLETED', version: 5,
};

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe('TaskDialog — create mode', () => {
  it('renders the title input', () => {
    wrap(<TaskDialog mode="create" importance="HIGH" urgency="HIGH" onClose={vi.fn()} />);
    expect(screen.getByLabelText(/title/i)).toBeInTheDocument();
  });

  it('pre-fills importance and urgency from props', () => {
    wrap(<TaskDialog mode="create" importance="LOW" urgency="HIGH" onClose={vi.fn()} />);
    expect(screen.getByLabelText(/importance/i)).toHaveValue('LOW');
    expect(screen.getByLabelText(/urgency/i)).toHaveValue('HIGH');
  });

  it('shows a validation error when submitted with empty title', async () => {
    wrap(<TaskDialog mode="create" onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/required/i);
  });

  it('calls onClose when Cancel is clicked', async () => {
    const onClose = vi.fn();
    wrap(<TaskDialog mode="create" onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});

describe('TaskDialog — edit mode', () => {
  it('pre-fills fields with the task data', () => {
    wrap(<TaskDialog mode="edit" task={PLANNED_TASK} onClose={vi.fn()} />);
    expect(screen.getByLabelText(/title/i)).toHaveValue('Fix the bug');
    expect(screen.getByLabelText(/importance/i)).toHaveValue('HIGH');
    expect(screen.getByLabelText(/urgency/i)).toHaveValue('MEDIUM');
  });

  it('displays the current task state', () => {
    wrap(<TaskDialog mode="edit" task={PLANNED_TASK} onClose={vi.fn()} />);
    expect(screen.getByTestId('task-current-state')).toHaveTextContent('PLANNED');
  });

  it('shows a transition button for a PLANNED task', () => {
    wrap(<TaskDialog mode="edit" task={PLANNED_TASK} onClose={vi.fn()} />);
    expect(screen.getByTestId('transition-btn-IN_PROGRESS')).toBeInTheDocument();
  });

  it('shows no transition button for a COMPLETED task', () => {
    wrap(<TaskDialog mode="edit" task={COMPLETED_TASK} onClose={vi.fn()} />);
    expect(screen.queryByTestId(/^transition-btn-/)).not.toBeInTheDocument();
  });

  it('shows the Delete button in edit mode', () => {
    wrap(<TaskDialog mode="edit" task={PLANNED_TASK} onClose={vi.fn()} />);
    expect(screen.getByTestId('delete-task')).toBeInTheDocument();
  });
});
