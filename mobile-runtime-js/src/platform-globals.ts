type MutableGlobal = typeof globalThis & {
  structuredClone?: <T>(value: T) => T;
  TextEncoder?: typeof TextEncoder;
  TextDecoder?: typeof TextDecoder;
  AbortController?: typeof AbortController;
  queueMicrotask?: (callback: () => void) => void;
};

class MobileTextEncoder {
  readonly encoding = "utf-8";

  encode(input = ""): Uint8Array {
    const encoded = unescape(encodeURIComponent(input));
    const bytes = new Uint8Array(encoded.length);
    for (let index = 0; index < encoded.length; index += 1) {
      bytes[index] = encoded.charCodeAt(index);
    }
    return bytes;
  }
}

class MobileTextDecoder {
  readonly encoding = "utf-8";

  decode(input: ArrayBufferView | ArrayBuffer = new Uint8Array()): string {
    const view = input instanceof ArrayBuffer
      ? new Uint8Array(input)
      : new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    let encoded = "";
    for (const byte of view) {
      encoded += String.fromCharCode(byte);
    }
    return decodeURIComponent(escape(encoded));
  }
}

type AbortListener = (event: { type: "abort"; target: MobileAbortSignal }) => void;

class MobileAbortSignal {
  aborted = false;
  reason: unknown;
  onabort: AbortListener | null = null;
  private readonly listeners = new Set<AbortListener>();

  addEventListener(type: string, listener: AbortListener): void {
    if (type === "abort") this.listeners.add(listener);
  }

  removeEventListener(type: string, listener: AbortListener): void {
    if (type === "abort") this.listeners.delete(listener);
  }

  throwIfAborted(): void {
    if (this.aborted) {
      throw this.reason instanceof Error ? this.reason : new Error("Operation aborted");
    }
  }

  dispatchAbort(reason: unknown): void {
    if (this.aborted) return;
    this.aborted = true;
    this.reason = reason ?? new Error("Operation aborted");
    const event = { type: "abort" as const, target: this };
    this.onabort?.(event);
    for (const listener of this.listeners) listener(event);
  }
}

class MobileAbortController {
  readonly signal = new MobileAbortSignal();

  abort(reason?: unknown): void {
    this.signal.dispatchAbort(reason);
  }
}

export function installPiMobilePlatformGlobals(): void {
  if (typeof Object.hasOwn !== "function") {
    Object.hasOwn = (object: object, property: PropertyKey): boolean =>
      Object.prototype.hasOwnProperty.call(object, property);
  }

  const mobileGlobal = globalThis as MutableGlobal;
  mobileGlobal.structuredClone ??= <T>(value: T): T =>
    JSON.parse(JSON.stringify(value)) as T;
  mobileGlobal.TextEncoder ??= MobileTextEncoder as unknown as typeof TextEncoder;
  mobileGlobal.TextDecoder ??= MobileTextDecoder as unknown as typeof TextDecoder;
  mobileGlobal.AbortController ??= MobileAbortController as unknown as typeof AbortController;
  mobileGlobal.queueMicrotask ??= (callback: () => void): void => {
    void Promise.resolve().then(callback);
  };
}

export function platformCapabilities(): Record<string, boolean> {
  return {
    abortController: typeof globalThis.AbortController === "function",
    objectHasOwn: typeof Object.hasOwn === "function",
    queueMicrotask: typeof globalThis.queueMicrotask === "function",
    secureRandom: typeof globalThis.crypto?.getRandomValues === "function",
    structuredClone: typeof globalThis.structuredClone === "function",
    textDecoder: typeof globalThis.TextDecoder === "function",
    textEncoder: typeof globalThis.TextEncoder === "function",
    timers: typeof globalThis.setTimeout === "function" && typeof globalThis.clearTimeout === "function",
  };
}
