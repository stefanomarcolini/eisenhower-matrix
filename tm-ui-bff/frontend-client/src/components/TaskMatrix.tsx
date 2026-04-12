import { useState } from 'react';
import { ArrowUpDown, RotateCcw } from 'lucide-react';
import { useTaskMatrix } from '../hooks/useTasks';
import { TaskCard } from './TaskCard';
import type { Priority, Task } from '../api/tasks';
import type { components } from '../api/schema';

type MatrixCell = components['schemas']['MatrixCell'];

const PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];

function getTasksForCell(
  cells: MatrixCell[],
  row: Priority,
  col: Priority,
  axisSwapped: boolean,
): Task[] {
  const [imp, urg] = axisSwapped ? [col, row] : [row, col];
  return cells.find((c) => c.importance === imp && c.urgency === urg)?.tasks ?? [];
}

interface Props {
  onCreateTask: (importance: Priority, urgency: Priority) => void;
  onEditTask:   (task: Task)                               => void;
}

export function TaskMatrix({ onCreateTask, onEditTask }: Props) {
  const { data, isLoading, isError } = useTaskMatrix();
  const [axisSwapped, setAxisSwapped] = useState(false);
  const [sortAsc,     setSortAsc]     = useState(false); // false = High→Low (default)

  const ordered = sortAsc ? [...PRIORITIES].reverse() : PRIORITIES;
  const rowLabel = axisSwapped ? 'Urgency'    : 'Importance';
  const colLabel = axisSwapped ? 'Importance' : 'Urgency';

  if (isLoading) return <div className="p-8 text-center text-gray-500">Loading…</div>;
  if (isError)   return <div className="p-8 text-center text-red-500">Failed to load tasks.</div>;

  const cells = data?.cells ?? [];

  function handleCellClick(row: Priority, col: Priority) {
    const [importance, urgency]: [Priority, Priority] = axisSwapped ? [col, row] : [row, col];
    onCreateTask(importance, urgency);
  }

  return (
    <div data-testid="task-matrix">
      <div className="flex items-center gap-4 mb-4">
        <button
          type="button"
          onClick={() => setAxisSwapped((v) => !v)}
          data-testid="swap-axes"
          className="flex items-center gap-1.5 text-sm text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-white"
        >
          <RotateCcw size={15} />
          Swap axes
        </button>
        <button
          type="button"
          onClick={() => setSortAsc((v) => !v)}
          data-testid="toggle-sort"
          className="flex items-center gap-1.5 text-sm text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-white"
        >
          <ArrowUpDown size={15} />
          {sortAsc ? 'Low → High' : 'High → Low'}
        </button>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full border-collapse">
          <thead>
            <tr>
              <th className="w-24 text-xs font-semibold text-gray-500 dark:text-gray-400 text-left pb-2 pr-2">
                {rowLabel} ↓ / {colLabel} →
              </th>
              {ordered.map((col) => (
                <th
                  key={col}
                  className="text-xs font-semibold text-gray-500 dark:text-gray-400 pb-2 px-2 text-center"
                >
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {ordered.map((row) => (
              <tr key={row}>
                <td className="text-xs font-semibold text-gray-500 dark:text-gray-400 pr-2 py-1 align-top">
                  {row}
                </td>
                {ordered.map((col) => {
                  const tasks = getTasksForCell(cells, row, col, axisSwapped);
                  return (
                    <td
                      key={col}
                      data-testid={`cell-${row}-${col}`}
                      onClick={() => handleCellClick(row, col)}
                      className="border border-gray-200 dark:border-gray-700 rounded p-1.5 align-top min-w-[140px] cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors"
                    >
                      <div className="space-y-1.5">
                        {tasks.map((task) => (
                          <TaskCard
                            key={task.id}
                            task={task}
                            onClick={() => onEditTask(task)}
                          />
                        ))}
                        {tasks.length === 0 && (
                          <span className="text-xs text-gray-300 dark:text-gray-600 select-none">
                            + add
                          </span>
                        )}
                      </div>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
