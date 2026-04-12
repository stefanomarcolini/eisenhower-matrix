import { apiClient } from './client';
import type { components } from './schema';

export type Task          = components['schemas']['Task'];
export type TaskState     = components['schemas']['TaskState'];
export type Priority      = components['schemas']['Priority'];
export type CreateTaskReq = components['schemas']['CreateTaskRequest'];
export type UpdateTaskReq = components['schemas']['UpdateTaskRequest'];
export type PatchTaskReq  = components['schemas']['PatchTaskRequest'];
export type MatrixResp    = components['schemas']['TaskMatrixResponse'];
export type PagedTasks    = components['schemas']['PagedTaskResponse'];

export async function getTaskMatrix(): Promise<MatrixResp> {
  const { data } = await apiClient.get<MatrixResp>('/api/v1/tasks/matrix');
  return data;
}

export async function listTasks(params?: {
  state?: TaskState;
  importance?: Priority;
  urgency?: Priority;
  cursor?: string;
}): Promise<PagedTasks> {
  const { data } = await apiClient.get<PagedTasks>('/api/v1/tasks', { params });
  return data;
}

export async function createTask(req: CreateTaskReq): Promise<Task> {
  const { data } = await apiClient.post<Task>('/api/v1/tasks', req);
  return data;
}

export async function updateTask(id: string, req: UpdateTaskReq): Promise<Task> {
  const { data } = await apiClient.put<Task>(`/api/v1/tasks/${id}`, req);
  return data;
}

export async function patchTask(id: string, req: PatchTaskReq): Promise<Task> {
  const { data } = await apiClient.patch<Task>(`/api/v1/tasks/${id}`, req);
  return data;
}

export async function deleteTask(id: string): Promise<void> {
  await apiClient.delete(`/api/v1/tasks/${id}`);
}
