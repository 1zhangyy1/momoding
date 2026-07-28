import { PROTOCOL_VERSION } from "./envelope.js";
import type {
  SnapshotDeviceCall,
  StreamCursor,
  TaskRecoveryState,
  TaskRunState,
} from "./snapshot.js";

export const P1B_SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES = 768 * 1024;
export const P1B_SNAPSHOT_MAX_LOGICAL_BYTES = 64 * 1024 * 1024;
export const P1B_SNAPSHOT_MAX_PAGES = 256;
export const P1B_HISTORY_MAX_BYTES = 8 * 1024 * 1024;

export type SnapshotTransferMode = "replace" | "prepend_history";

export interface SnapshotWindow {
  messageStartIndex: number;
  messageEndExclusive: number;
  hasMoreBefore: boolean;
  historyCursor?: string;
}

export interface TaskSnapshotBegin<TQueueEntry = unknown, TAttention = unknown> {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "task.snapshot.begin";
  requestId?: string;
  transferMode: SnapshotTransferMode;
  taskId: string;
  snapshotVersion: number;
  recoveryState: TaskRecoveryState;
  runState: TaskRunState;
  piSessionId: string;
  isStreaming: boolean;
  queue: TQueueEntry[];
  pendingAttention: TAttention[];
  deviceCalls: SnapshotDeviceCall[];
  cursor: StreamCursor;
  totalMessages: number;
  window: SnapshotWindow;
}

export interface TaskSnapshotPage<TMessage = unknown> {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "task.snapshot.page";
  taskId: string;
  snapshotVersion: number;
  pageIndex: number;
  messageStartIndex: number;
  messages: TMessage[];
}

export interface TaskSnapshotEnd {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "task.snapshot.end";
  taskId: string;
  snapshotVersion: number;
  pageCount: number;
  sha256: string;
}

export type TaskSnapshotTransferFrame =
  | TaskSnapshotBegin
  | TaskSnapshotPage
  | TaskSnapshotEnd;
