import { useState } from 'react';
import { LayoutGrid, List, Plus } from 'lucide-react';
import { TaskMatrix }            from '../components/TaskMatrix';
import { TaskList }              from '../components/TaskList';
import { TaskDialog }            from '../components/TaskDialog';
import { PasswordWarningBanner } from '../components/PasswordWarningBanner';
import { useSession }            from '../hooks/useSession';
import type { Task, Priority }   from '../api/tasks';

type View = 'matrix' | 'list';

interface CreateState { mode: 'create'; importance: Priority; urgency: Priority }
interface EditState   { mode: 'edit';   task: Task }
type DialogState = CreateState | EditState | null;

export default function DashboardPage() {
  const [view,            setView]            = useState<View>('matrix');
  const [dialog,          setDialog]          = useState<DialogState>(null);
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const { data: session } = useSession();

  function openCreate(importance: Priority, urgency: Priority) {
    setDialog({ mode: 'create', importance, urgency });
  }

  function openEdit(task: Task) {
    setDialog({ mode: 'edit', task });
  }

  function openCreateDefault() {
    setDialog({ mode: 'create', importance: 'MEDIUM', urgency: 'MEDIUM' });
  }

  return (
    <div>
      {session?.passwordWarning && !bannerDismissed && (
        <PasswordWarningBanner onDismiss={() => setBannerDismissed(true)} />
      )}
      <div className="max-w-6xl mx-auto px-4 py-8">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">Tasks</h1>
          <div className="flex items-center gap-2">
            {/* View toggle */}
            <div className="flex rounded-md border border-gray-300 dark:border-gray-600 overflow-hidden">
              <button
                type="button"
                onClick={() => setView('matrix')}
                data-testid="view-matrix"
                className={`flex items-center gap-1.5 px-3 py-1.5 text-sm ${
                  view === 'matrix'
                    ? 'bg-indigo-600 text-white'
                    : 'bg-white dark:bg-gray-900 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800'
                }`}
              >
                <LayoutGrid size={15} />
                Matrix
              </button>
              <button
                type="button"
                onClick={() => setView('list')}
                data-testid="view-list"
                className={`flex items-center gap-1.5 px-3 py-1.5 text-sm ${
                  view === 'list'
                    ? 'bg-indigo-600 text-white'
                    : 'bg-white dark:bg-gray-900 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800'
                }`}
              >
                <List size={15} />
                List
              </button>
            </div>

            {/* New task button */}
            <button
              type="button"
              onClick={openCreateDefault}
              data-testid="new-task-btn"
              className="flex items-center gap-1.5 px-4 py-1.5 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700"
            >
              <Plus size={15} />
              New Task
            </button>
          </div>
        </div>

        {/* Content */}
        {view === 'matrix' ? (
          <TaskMatrix onCreateTask={openCreate} onEditTask={openEdit} />
        ) : (
          <TaskList onEditTask={openEdit} />
        )}

        {/* Dialog */}
        {dialog?.mode === 'create' && (
          <TaskDialog
            mode="create"
            importance={dialog.importance}
            urgency={dialog.urgency}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog?.mode === 'edit' && (
          <TaskDialog
            mode="edit"
            task={dialog.task}
            onClose={() => setDialog(null)}
          />
        )}
      </div>
    </div>
  );
}
