export const WIRE_ERROR_CODES = [
  "PROTOCOL_MISMATCH",
  "UNAUTHORIZED",
  "BAD_REQUEST",
  "FRAME_TOO_LARGE",
  "PAYLOAD_TOO_LARGE",
  "TASK_NOT_FOUND",
  "SESSION_BUSY",
  "INVALID_SESSION_STATE",
  "CURSOR_INVALID",
  "REPLAY_EXPIRED",
  "RECOVERY_REQUIRED",
  "DEVICE_OFFLINE",
  "TOOL_TIMEOUT",
  "ABORTED",
] as const;

export type WireErrorCode = (typeof WIRE_ERROR_CODES)[number];

export interface WireErrorBody {
  code: WireErrorCode;
  message: string;
  retryable: boolean;
}
