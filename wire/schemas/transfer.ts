import { PROTOCOL_VERSION } from "./envelope.js";

export const RELIABILITY_DIRECT_PI_EVENT_MAX_BYTES = 1024 * 1024;
export const RELIABILITY_CHUNK_MAX_PHYSICAL_FRAME_BYTES = 768 * 1024;
export const RELIABILITY_CHUNK_MAX_TRANSFER_BYTES = 64 * 1024 * 1024;
export const RELIABILITY_CHUNK_MAX_COUNT = 256;
export const RELIABILITY_CHUNK_TIMEOUT_MS = 30_000;

interface ChunkStartBase {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "transport.chunk.start";
  transferId: string;
  taskId: string;
  totalBytes: number;
  sha256: string;
  chunkCount: number;
}

export interface PiEventChunkStart extends ChunkStartBase {
  contentKind: "pi.event";
  streamId: string;
  sequence: number;
}

export interface SnapshotPageChunkStart extends ChunkStartBase {
  contentKind: "task.snapshot.page";
  snapshotVersion: number;
  pageIndex: number;
}

export type TransportChunkStart = PiEventChunkStart | SnapshotPageChunkStart;

export interface TransportChunkData {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "transport.chunk.data";
  transferId: string;
  chunkIndex: number;
  data: string;
}

export interface TransportChunkEnd {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "transport.chunk.end";
  transferId: string;
}

export type TransportChunkFrame =
  | TransportChunkStart
  | TransportChunkData
  | TransportChunkEnd;
