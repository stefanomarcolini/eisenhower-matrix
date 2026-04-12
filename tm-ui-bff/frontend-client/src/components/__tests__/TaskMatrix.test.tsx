import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TaskMatrix } from '../TaskMatrix';
import type { components } from '../../api/schema';

// Prevent real API calls
vi.mock('../../api/tasks', () => ({
  getTaskMatrix: vi.fn(),
  patchTask:     vi.fn(),
}));

type MatrixResp = components['schemas']['TaskMatrixResponse'];

const TASK_HIGH_HIGH = {
  id: 'task-1', title: 'Urgent Important Task', state: 'PLANNED' as const,
  importance: 'HIGH' as const, urgency: 'HIGH' as const,
  version: 0, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

const TASK_LOW_LOW = {
  id: 'task-2', title: 'Low Priority Task', state: 'IN_PROGRESS' as const,
  importance: 'LOW' as const, urgency: 'LOW' as const,
  version: 1, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

const TASK_HIGH_LOW = {
  id: 'task-3', title: 'High Importance Low Urgency', state: 'PLANNED' as const,
  importance: 'HIGH' as const, urgency: 'LOW' as const,
  version: 0, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

const MATRIX_DATA: MatrixResp = {
  cells: [
    { importance: 'HIGH', urgency: 'HIGH', tasks: [TASK_HIGH_HIGH] },
    { importance: 'LOW',  urgency: 'LOW',  tasks: [TASK_LOW_LOW]   },
    // 7 remaining cells absent (treated as empty by the component)
  ],
};

// Minimal matrix with one off-diagonal task for axis-swap correctness tests.
const MATRIX_DATA_SWAP: MatrixResp = {
  cells: [
    { importance: 'HIGH', urgency: 'LOW', tasks: [TASK_HIGH_LOW] },
  ],
};

function renderMatrix(
  matrixData: MatrixResp | null = MATRIX_DATA,
  onCreateTask = vi.fn(),
  onEditTask   = vi.fn(),
) {
  // Pre-populate the query cache so the component renders without a real fetch
  const qc = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: Infinity,
        refetchOnMount: false,
      },
    },
  });
  if (matrixData) {
    qc.setQueryData(['tasks', 'matrix'], matrixData);
  }
  render(
    <QueryClientProvider client={qc}>
      <TaskMatrix onCreateTask={onCreateTask} onEditTask={onEditTask} />
    </QueryClientProvider>,
  );
  return { onCreateTask, onEditTask };
}

describe('TaskMatrix', () => {
  it('renders a 3×3 grid with 9 cells', () => {
    renderMatrix();
    // 3 rows × 3 columns = 9 data cells
    const cells = screen.getAllByTestId(/^cell-/);
    expect(cells).toHaveLength(9);
  });

  it('shows a task title in the correct cell', () => {
    renderMatrix();
    const cell = screen.getByTestId('cell-HIGH-HIGH');
    expect(cell).toHaveTextContent('Urgent Important Task');
  });

  it('shows an empty-cell placeholder for cells with no tasks', () => {
    renderMatrix();
    const cell = screen.getByTestId('cell-HIGH-MEDIUM');
    expect(cell).toHaveTextContent('+ add');
  });

  it('calls onCreateTask with correct importance and urgency when empty cell is clicked', () => {
    const { onCreateTask } = renderMatrix();
    fireEvent.click(screen.getByTestId('cell-MEDIUM-LOW'));
    expect(onCreateTask).toHaveBeenCalledWith('MEDIUM', 'LOW');
  });

  it('calls onEditTask when a task card is clicked', () => {
    const { onEditTask } = renderMatrix();
    fireEvent.click(screen.getByText('Urgent Important Task'));
    expect(onEditTask).toHaveBeenCalledWith(TASK_HIGH_HIGH);
  });

  it('swaps row/column header labels when swap-axes is clicked', () => {
    renderMatrix();
    expect(screen.getByText(/Importance.*Urgency/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('swap-axes'));
    expect(screen.getByText(/Urgency.*Importance/)).toBeInTheDocument();
  });

  it('toggles sort order label when toggle-sort is clicked', () => {
    renderMatrix();
    const btn = screen.getByTestId('toggle-sort');
    expect(btn).toHaveTextContent('High → Low');
    fireEvent.click(btn);
    expect(btn).toHaveTextContent('Low → High');
  });

  // ── Axis swap correctness ────────────────────────────────────────────────
  // A task with importance=HIGH, urgency=LOW should appear in cell-HIGH-LOW
  // before the swap (rows = Importance, cols = Urgency).
  // After the swap (rows = Urgency, cols = Importance) the same task should
  // move to cell-LOW-HIGH (row=LOW=urgency, col=HIGH=importance).

  it('places a HIGH-importance/LOW-urgency task in cell-HIGH-LOW before swap', () => {
    renderMatrix(MATRIX_DATA_SWAP);
    expect(screen.getByTestId('cell-HIGH-LOW')).toHaveTextContent('High Importance Low Urgency');
    expect(screen.getByTestId('cell-LOW-HIGH')).not.toHaveTextContent('High Importance Low Urgency');
  });

  it('moves HIGH-importance/LOW-urgency task to cell-LOW-HIGH after axis swap', () => {
    renderMatrix(MATRIX_DATA_SWAP);
    fireEvent.click(screen.getByTestId('swap-axes'));
    // After swap: rows = Urgency axis, cols = Importance axis.
    // task(imp=HIGH, urg=LOW): needs row=urg=LOW and col=imp=HIGH → cell-LOW-HIGH
    expect(screen.getByTestId('cell-LOW-HIGH')).toHaveTextContent('High Importance Low Urgency');
    expect(screen.getByTestId('cell-HIGH-LOW')).not.toHaveTextContent('High Importance Low Urgency');
  });

  it('remaps onCreateTask importance/urgency correctly after axis swap', () => {
    const { onCreateTask } = renderMatrix();
    fireEvent.click(screen.getByTestId('swap-axes'));
    // Clicking cell-MEDIUM-LOW: row=MEDIUM, col=LOW
    // axisSwapped → [importance, urgency] = [col, row] = [LOW, MEDIUM]
    fireEvent.click(screen.getByTestId('cell-MEDIUM-LOW'));
    expect(onCreateTask).toHaveBeenCalledWith('LOW', 'MEDIUM');
  });
});
