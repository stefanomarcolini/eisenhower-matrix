import { usePatchTask } from '../hooks/useTasks';
import type { Task, TaskState } from '../api/tasks';

const STATE_COLORS: Record<TaskState, string> = {
  PLANNED:     'bg-blue-100   text-blue-800   dark:bg-blue-900   dark:text-blue-200',
  IN_PROGRESS: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200',
  COMPLETED:   'bg-green-100  text-green-800  dark:bg-green-900  dark:text-green-200',
  OVERDUE:     'bg-red-100    text-red-800    dark:bg-red-900    dark:text-red-200',
};

const TRANSITIONS: Partial<Record<TaskState, { label: string; next: TaskState }>> = {
  PLANNED:     { label: 'Start',    next: 'IN_PROGRESS' },
  IN_PROGRESS: { label: 'Complete', next: 'COMPLETED'   },
  OVERDUE:     { label: 'Reopen',   next: 'IN_PROGRESS' },
};

const PRIORITY_COLORS = {
  HIGH: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
  MEDIUM: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  LOW: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200',
} as const;

interface Props {
  task:    Task;
  onClick: () => void;
}

export function TaskCard({ task, onClick }: Props) {
  const patch      = usePatchTask();
  const transition = TRANSITIONS[task.state];

  function handleClick(e: React.MouseEvent) {
    e.stopPropagation();
    onClick();
  }

  function handleTransition(e: React.MouseEvent) {
    e.stopPropagation();
    if (!transition) return;
    patch.mutate({ id: task.id, req: { state: transition.next, version: task.version } });
  }

  return (
    <div
      role="button"
      tabIndex={0}
      data-testid="task-card"
      onClick={handleClick}
      onKeyDown={(e) => e.key === 'Enter' && onClick()}
      className="bg-white dark:bg-gray-800 rounded border border-gray-200 dark:border-gray-700 p-2 cursor-pointer hover:shadow-sm text-left w-full"
    >
      <p className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate">{task.title}</p>
      <div className="mt-1 flex items-center gap-1 flex-wrap">
        <span className={`text-xs px-1.5 py-0.5 rounded ${STATE_COLORS[task.state]}`}>
          {task.state.replace('_', '\u00a0')}
        </span>
        <span className={`text-xs px-1.5 py-0.5 rounded ${PRIORITY_COLORS[task.importance]}`}>
          Importance: {task.importance}
        </span>
        <span className={`text-xs px-1.5 py-0.5 rounded ${PRIORITY_COLORS[task.urgency]}`}>
          Urgency: {task.urgency}
        </span>
        {task.dueDate && (
          <span className="text-xs text-gray-500 dark:text-gray-400">{task.dueDate}</span>
        )}
      </div>
      {transition && (
        <button
          type="button"
          onClick={handleTransition}
          disabled={patch.isPending}
          data-testid={`transition-${transition.next}`}
          className="mt-1.5 text-xs text-indigo-600 dark:text-indigo-400 hover:underline disabled:opacity-50"
        >
          {transition.label}
        </button>
      )}
    </div>
  );
}
