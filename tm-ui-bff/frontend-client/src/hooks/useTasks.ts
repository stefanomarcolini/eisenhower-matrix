import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getTaskMatrix, listTasks, createTask, updateTask, patchTask, deleteTask,
  type UpdateTaskReq, type PatchTaskReq, type TaskState, type Priority,
} from '../api/tasks';

export const MATRIX_KEY = ['tasks', 'matrix'] as const;
export const LIST_KEY   = ['tasks', 'list']   as const;

export function useTaskMatrix() {
  return useQuery({ queryKey: MATRIX_KEY, queryFn: getTaskMatrix });
}

export function useTaskList(params?: {
  state?: TaskState;
  importance?: Priority;
  urgency?: Priority;
  cursor?: string;
}) {
  return useQuery({ queryKey: [...LIST_KEY, params], queryFn: () => listTasks(params) });
}

function invalidateTasks(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: MATRIX_KEY });
  qc.invalidateQueries({ queryKey: LIST_KEY });
}

export function useCreateTask() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: createTask, onSuccess: () => invalidateTasks(qc) });
}

export function useUpdateTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, req }: { id: string; req: UpdateTaskReq }) => updateTask(id, req),
    onSuccess: () => invalidateTasks(qc),
  });
}

export function usePatchTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, req }: { id: string; req: PatchTaskReq }) => patchTask(id, req),
    onSuccess: () => invalidateTasks(qc),
  });
}

export function useDeleteTask() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: deleteTask, onSuccess: () => invalidateTasks(qc) });
}
