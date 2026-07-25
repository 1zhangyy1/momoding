import { PROTOCOL_VERSION } from "./envelope.js";

export type DeviceToolTerminal =
  | "succeeded"
  | "failed"
  | "rejected"
  | "cancelled"
  | "timed_out";

export type DeviceLedgerState =
  | "never_started"
  | "running"
  | "succeeded"
  | "failed"
  | "cancelled"
  | "unknown";

export type DeviceHostCallState =
  | "created"
  | "sent"
  | "running"
  | "terminal"
  | "reconciled";

export interface DeviceWireError {
  code: string;
  message: string;
  retryable: false;
}

export interface DeviceCapabilitiesReport {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.capabilities.report";
  requestId: string;
  commandId: string;
  deviceId: string;
  capabilityVersion: number;
  manifest: Record<string, unknown>;
  expiresAt: string;
}

export interface DeviceToolRequest {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.request";
  callId: string;
  taskId: string;
  piToolCallId: string;
  deviceId: string;
  toolName: string;
  arguments: unknown;
  sideEffect: boolean;
  operationId?: string;
  expiresAt: string;
  capabilityVersion: number;
}

export interface DeviceToolProgress {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.progress";
  callId: string;
  taskId: string;
  deviceId: string;
  progressSequence: number;
  phase: "received" | "running" | "awaiting_user" | "committing";
  summary?: string;
}

export interface DeviceToolResult {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.result";
  callId: string;
  taskId: string;
  deviceId: string;
  terminal: DeviceToolTerminal;
  result?: unknown;
  error?: DeviceWireError;
}

export interface DeviceToolCancel {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.cancel";
  callId: string;
  taskId: string;
  reason: "session_stop" | "tool_abort" | "timeout" | "host_shutdown";
}

export interface DeviceToolReconcileCall {
  callId: string;
  operationId?: string;
  toolName: string;
  lastKnownState: DeviceHostCallState;
}

export interface DeviceToolReconcileRequest {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.reconcile.request";
  requestId: string;
  taskId: string;
  deviceId: string;
  calls: DeviceToolReconcileCall[];
}

interface DeviceToolReconcileItemBase {
  callId: string;
  operationId?: string;
}

export type DeviceToolReconcileItem =
  | (DeviceToolReconcileItemBase & {
      state: "never_started" | "running" | "unknown";
    })
  | (DeviceToolReconcileItemBase & {
      state: "succeeded";
      resultSummary: unknown;
    })
  | (DeviceToolReconcileItemBase & {
      state: "failed" | "cancelled";
      error: DeviceWireError;
    });

export interface DeviceToolReconcileResult {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "device.tool.reconcile.result";
  requestId: string;
  commandId: string;
  taskId: string;
  deviceId: string;
  results: DeviceToolReconcileItem[];
}

export type DeviceClientFrame =
  | DeviceCapabilitiesReport
  | DeviceToolProgress
  | DeviceToolResult
  | DeviceToolReconcileResult;

export type DeviceServerFrame =
  | DeviceToolRequest
  | DeviceToolCancel
  | DeviceToolReconcileRequest;
