export const P1A_MAX_SNAPSHOT_BYTES = 768 * 1024;

export type TaskRunState =
  | "idle"
  | "starting"
  | "running"
  | "waiting"
  | "stopping"
  | "stopped"
  | "completed"
  | "failed"
  | "interrupted";

export type TaskRecoveryState = "normal" | "interrupted" | "reconciling_device_calls";

export interface StreamCursor {
  streamId: string;
  highWatermarkSequence: number;
  oldestReplayableSequence: number;
}

export interface SnapshotDeviceCall {
  callId: string;
  operationId?: string;
  state: string;
}

/**
 * P1A product snapshot. Messages remain raw Pi message JSON; this type does
 * not copy or redefine Pi's event union.
 */
export interface TaskSnapshot<TMessage = unknown, TQueueEntry = unknown, TAttention = unknown> {
  kind: "task.snapshot";
  requestId?: string;
  taskId: string;
  snapshotVersion: number;
  recoveryState: TaskRecoveryState;
  runState: TaskRunState;
  piSessionId: string;
  pi: {
    messages: TMessage[];
    isStreaming: boolean;
    queue: TQueueEntry[];
  };
  pendingAttention: TAttention[];
  deviceCalls: SnapshotDeviceCall[];
  cursor: StreamCursor;
}

export class SnapshotPayloadTooLargeError extends Error {
  readonly code = "PAYLOAD_TOO_LARGE" as const;

  constructor(
    readonly actualBytes: number,
    readonly maxBytes = P1A_MAX_SNAPSHOT_BYTES,
  ) {
    super(`Task snapshot is ${actualBytes} bytes; P1A limit is ${maxBytes} bytes`);
    this.name = "SnapshotPayloadTooLargeError";
  }
}

export function serializeP1ATaskSnapshot(snapshot: TaskSnapshot): string {
  const serialized = JSON.stringify(snapshot);
  const actualBytes = Buffer.byteLength(serialized, "utf8");
  if (actualBytes > P1A_MAX_SNAPSHOT_BYTES) {
    throw new SnapshotPayloadTooLargeError(actualBytes);
  }
  return serialized;
}
