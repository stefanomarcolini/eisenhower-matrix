import * as Dialog from '@radix-ui/react-dialog';
import { useForm } from 'react-hook-form';
import { X } from 'lucide-react';
import { useCreateTask, useUpdateTask, usePatchTask, useDeleteTask } from '../hooks/useTasks';
import type { Task, Priority, TaskState } from '../api/tasks';

const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH'];

const TRANSITIONS: Partial<Record<TaskState, { label: string; next: TaskState }>> = {
  PLANNED:     { label: 'Start',    next: 'IN_PROGRESS' },
  IN_PROGRESS: { label: 'Complete', next: 'COMPLETED'   },
  OVERDUE:     { label: 'Reopen',   next: 'IN_PROGRESS' },
};

interface FormValues {
  title:       string;
  description: string;
  importance:  Priority;
  urgency:     Priority;
  dueDate:     string;
}

interface CreateProps {
  mode:        'create';
  importance?: Priority;
  urgency?:    Priority;
  onClose:     () => void;
}

interface EditProps {
  mode:    'edit';
  task:    Task;
  onClose: () => void;
}

type Props = CreateProps | EditProps;

export function TaskDialog(props: Props) {
  const { mode, onClose } = props;
  const task = mode === 'edit' ? props.task : undefined;

  const createTask = useCreateTask();
  const updateTask = useUpdateTask();
  const patchTask  = usePatchTask();
  const deleteTask = useDeleteTask();

  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
    defaultValues: {
      title:       task?.title       ?? '',
      description: task?.description ?? '',
      importance:  task?.importance  ?? (mode === 'create' ? props.importance ?? 'MEDIUM' : 'MEDIUM'),
      urgency:     task?.urgency     ?? (mode === 'create' ? props.urgency    ?? 'MEDIUM' : 'MEDIUM'),
      dueDate:     task?.dueDate     ?? '',
    },
  });

  const isPending = createTask.isPending || updateTask.isPending || patchTask.isPending || deleteTask.isPending;
  const transition = task ? TRANSITIONS[task.state] : undefined;

  function onSubmit(values: FormValues) {
    const payload = {
      title:       values.title,
      description: values.description || null,
      importance:  values.importance,
      urgency:     values.urgency,
      dueDate:     values.dueDate || null,
    };

    if (mode === 'create') {
      createTask.mutate(payload, { onSuccess: onClose });
    } else {
      updateTask.mutate(
        { id: task!.id, req: { ...payload, version: task!.version } },
        { onSuccess: onClose },
      );
    }
  }

  function handleTransition() {
    if (!task || !transition) return;
    patchTask.mutate(
      { id: task.id, req: { state: transition.next, version: task.version } },
      { onSuccess: onClose },
    );
  }

  function handleDelete() {
    if (!task) return;
    deleteTask.mutate(task.id, { onSuccess: onClose });
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Content
          data-testid="task-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
        >
          <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex items-center justify-between mb-5">
              <Dialog.Title className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                {mode === 'create' ? 'New Task' : 'Edit Task'}
              </Dialog.Title>
              <Dialog.Description className="sr-only">
                {mode === 'create'
                  ? 'Create a task by entering title, priority, and optional details.'
                  : 'Edit task details, change state, or delete the task.'}
              </Dialog.Description>
              <Dialog.Close asChild>
                <button
                  type="button"
                  aria-label="Close"
                  className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200"
                >
                  <X size={18} />
                </button>
              </Dialog.Close>
            </div>

            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
              {/* Title */}
              <div>
                <label htmlFor="task-title" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Title <span className="text-red-500">*</span>
                </label>
                <input
                  id="task-title"
                  {...register('title', { required: 'Title is required', maxLength: { value: 255, message: 'Max 255 characters' } })}
                  className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                {errors.title && (
                  <p role="alert" className="mt-1 text-xs text-red-600 dark:text-red-400">{errors.title.message}</p>
                )}
              </div>

              {/* Description */}
              <div>
                <label htmlFor="task-description" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Description
                </label>
                <textarea
                  id="task-description"
                  {...register('description')}
                  rows={3}
                  className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              {/* Importance + Urgency */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="task-importance" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                    Importance
                  </label>
                  <select
                    id="task-importance"
                    {...register('importance')}
                    className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  >
                    {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                  </select>
                </div>
                <div>
                  <label htmlFor="task-urgency" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                    Urgency
                  </label>
                  <select
                    id="task-urgency"
                    {...register('urgency')}
                    className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  >
                    {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                  </select>
                </div>
              </div>

              {/* Due Date */}
              <div>
                <label htmlFor="task-due-date" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Due Date
                </label>
                <input
                  id="task-due-date"
                  type="date"
                  {...register('dueDate')}
                  className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              {/* Current state (edit mode) */}
              {task && (
                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-500 dark:text-gray-400">State:</span>
                  <span
                    data-testid="task-current-state"
                    className="text-sm font-medium text-gray-900 dark:text-gray-100"
                  >
                    {task.state.replace('_', '\u00a0')}
                  </span>
                  {transition && (
                    <button
                      type="button"
                      onClick={handleTransition}
                      disabled={patchTask.isPending}
                      data-testid={`transition-btn-${transition.next}`}
                      className="ml-2 text-sm text-indigo-600 dark:text-indigo-400 hover:underline disabled:opacity-50"
                    >
                      → {transition.label}
                    </button>
                  )}
                </div>
              )}

              {/* Footer buttons */}
              <div className="flex items-center justify-between pt-2">
                {mode === 'edit' && (
                  <button
                    type="button"
                    onClick={handleDelete}
                    disabled={deleteTask.isPending}
                    data-testid="delete-task"
                    className="text-sm text-red-600 dark:text-red-400 hover:underline disabled:opacity-50"
                  >
                    Delete
                  </button>
                )}
                <div className={`flex gap-2 ${mode === 'create' ? 'ml-auto' : ''}`}>
                  <button
                    type="button"
                    onClick={onClose}
                    className="px-4 py-2 text-sm rounded-md border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={isPending}
                    className="px-4 py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
                  >
                    {mode === 'create' ? 'Create' : 'Save'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
