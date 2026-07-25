export const PROTOCOL_VERSION = 1 as const;
export const PI_VERSION = "0.80.6" as const;

/**
 * Transport framing around one unmodified Pi AgentSessionEvent.
 *
 * TEvent deliberately defaults to unknown: Wire does not copy, rename, trim,
 * or replace Pi's event union. The Pi Host supplies the locked SDK event type
 * at the production boundary and Kotlin retains the event as raw JSON.
 */
export interface PiEventEnvelope<TEvent = unknown> {
  protocolVersion: typeof PROTOCOL_VERSION;
  kind: "pi.event";
  taskId: string;
  piSessionId: string;
  piVersion: typeof PI_VERSION;
  streamId: string;
  sequence: number;
  emittedAt: string;
  requestId?: string;
  event: TEvent;
}
