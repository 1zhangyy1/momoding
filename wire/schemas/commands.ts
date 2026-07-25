import type { WireErrorBody } from "./errors.js";
import type { DeviceClientFrame, DeviceServerFrame } from "./device-tools.js";
import type { PiEventEnvelope } from "./envelope.js";
import type {
  PiEventAck,
  PiReplayComplete,
  PiResyncRequired,
} from "./reliability.js";
import type { TaskSnapshot } from "./snapshot.js";
import type { TaskRecoveryState, TaskRunState } from "./snapshot.js";
import type { TaskSnapshotTransferFrame } from "./snapshot-transfer.js";
import type { TransportChunkFrame } from "./transfer.js";

export const CORE_HELLO_TIMEOUT_MS = 5_000;
export const CORE_HEARTBEAT_INTERVAL_MS = 20_000;
export const CORE_PONG_TIMEOUT_MS = 60_000;
export const WIRE_MAX_PHYSICAL_FRAME_BYTES = 1_048_576;

export interface ResumeCursor {
  taskId: string;
  streamId: string;
  lastAckedSequence: number;
}

export interface ClientHello {
  protocolVersion: 1;
  kind: "hello";
  requestId: string;
  clientInstanceId: string;
  deviceId: string;
  clientVersion: string;
  auth: {
    scheme: "dev-token" | "device-credential";
    credential: string;
  };
  resume: ResumeCursor[];
}

export interface HelloAccepted {
  protocolVersion: 1;
  kind: "hello.accepted";
  requestId: string;
  connectionId: string;
  serverVersion: string;
  piVersion: "0.80.6";
  heartbeatIntervalMs: typeof CORE_HEARTBEAT_INTERVAL_MS;
  maxFrameBytes: typeof WIRE_MAX_PHYSICAL_FRAME_BYTES;
}

interface RequestFrame {
  protocolVersion: 1;
  requestId: string;
}

export interface TaskCreateCommand extends RequestFrame {
  kind: "task.create";
  commandId: string;
  draftId: string;
  title?: string;
}

export interface TaskListCommand extends RequestFrame {
  kind: "task.list";
  cursor?: string;
  limit: number;
}

export interface TaskListSummary {
  taskId: string;
  title?: string;
  updatedAt: string;
  runState: TaskRunState;
  recoveryState: TaskRecoveryState;
  snapshotVersion: number;
}

export interface TaskListResponseData {
  tasks: TaskListSummary[];
  listRevision: number;
  nextCursor?: string;
}

export interface TaskOpenCommand extends RequestFrame {
  kind: "task.open";
  taskId: string;
}

export interface TaskSnapshotRequest extends RequestFrame {
  kind: "task.snapshot.request";
  taskId: string;
  knownSnapshotVersion?: number;
}

export interface TaskHistoryRequest extends RequestFrame {
  kind: "task.history.request";
  taskId: string;
  snapshotVersion: number;
  beforeCursor: string;
  limitBytes: number;
}

export interface SessionPromptCommand extends RequestFrame {
  kind: "session.prompt";
  commandId: string;
  taskId: string;
  text: string;
}

export interface SessionSteerCommand extends RequestFrame {
  kind: "session.steer";
  commandId: string;
  taskId: string;
  text: string;
}

export interface SessionFollowUpCommand extends RequestFrame {
  kind: "session.follow_up";
  commandId: string;
  taskId: string;
  text: string;
}

export interface SessionStopCommand extends RequestFrame {
  kind: "session.stop";
  commandId: string;
  taskId: string;
  reason: string;
}

export type CoreClientCommand =
  | TaskCreateCommand
  | TaskListCommand
  | TaskOpenCommand
  | TaskSnapshotRequest
  | SessionPromptCommand
  | SessionSteerCommand
  | SessionFollowUpCommand
  | SessionStopCommand;

export type ReliabilityClientCommand =
  | CoreClientCommand
  | TaskHistoryRequest
  | PiEventAck
  | DeviceClientFrame;

export type CommandResponse =
  | {
      protocolVersion: 1;
      kind: "response";
      requestId: string;
      ok: true;
      data?: unknown;
    }
  | {
      protocolVersion: 1;
      kind: "response";
      requestId: string;
      ok: false;
      error: WireErrorBody;
    };

export interface WireErrorFrame {
  protocolVersion: 1;
  kind: "error";
  requestId?: string;
  error: WireErrorBody;
}

export type CoreServerFrame =
  | HelloAccepted
  | CommandResponse
  | WireErrorFrame
  | TaskSnapshot
  | PiEventEnvelope;

export type ReliabilityServerFrame =
  | CoreServerFrame
  | PiReplayComplete
  | PiResyncRequired
  | TransportChunkFrame
  | TaskSnapshotTransferFrame
  | DeviceServerFrame;
