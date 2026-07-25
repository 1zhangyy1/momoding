import { PROTOCOL_VERSION } from "./envelope.js";

export const RELIABILITY_REPLAY_MAX_EVENTS_PER_TASK = 4_096;
export const RELIABILITY_REPLAY_MAX_BYTES_PER_TASK = 8 * 1024 * 1024;
export const RELIABILITY_REPLAY_MAX_AGE_MS = 10 * 60 * 1_000;
export const RELIABILITY_REPLAY_MAX_BYTES_GLOBAL = 64 * 1024 * 1024;

export interface PiEventAck {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "pi.event.ack";
  taskId: string;
  streamId: string;
  throughSequence: number;
}

export interface PiReplayComplete {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "pi.replay.complete";
  taskId: string;
  streamId: string;
  replayedThroughSequence: number;
  liveFromSequence: number;
}

export type PiResyncReason = "stream_changed" | "cursor_expired" | "cursor_invalid";

export interface PiResyncRequired {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "pi.resync_required";
  taskId: string;
  reason: PiResyncReason;
  requestedStreamId: string;
  currentStreamId: string;
  snapshotVersion: number;
}
