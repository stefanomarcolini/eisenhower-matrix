import { useState } from 'react';
import { useTaskList } from '../hooks/useTasks';
import { TaskCard } from './TaskCard';
import type { Task } from '../api/tasks';

interface Props {
  onEditTask: (task: Task) => void;
}

export function TaskList({ onEditTask }: Props) {
  const [cursor, setCursor] = useState<string | undefined>();
  const { data, isLoading, isError } = useTaskList({ cursor });

  if (isLoading) return <div className="p-8 text-center text-gray-500">Loading…</div>;
  if (isError)   return <div className="p-8 text-center text-red-500">Failed to load tasks.</div>;

  const tasks = data?.data ?? [];

  return (
    <div data-testid="task-list">
      {tasks.length === 0 ? (
        <p className="text-center text-gray-500 dark:text-gray-400 py-12">No tasks yet.</p>
      ) : (
        <div className="space-y-2 max-w-xl mx-auto">
          {tasks.map((task) => (
            <TaskCard key={task.id} task={task} onClick={() => onEditTask(task)} />
          ))}
        </div>
      )}

      {(cursor || data?.nextCursor) && (
        <div className="flex justify-center gap-3 mt-6">
          {cursor && (
            <button
              type="button"
              onClick={() => setCursor(undefined)}
              className="px-4 py-2 text-sm rounded-md border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800"
            >
              ← First page
            </button>
          )}
          {data?.nextCursor && (
            <button
              type="button"
              onClick={() => setCursor(data.nextCursor ?? undefined)}
              className="px-4 py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700"
            >
              Next page →
            </button>
          )}
        </div>
      )}
    </div>
  );
}
