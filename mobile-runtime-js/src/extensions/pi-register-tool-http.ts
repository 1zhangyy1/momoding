export interface PiRegisterToolHttpPolicy {
  origins: string[];
  methods: Array<"GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE">;
  credentialSlots: Array<{
    slot: string;
    origin: string;
    placement: "authorization_bearer";
  }>;
}

export interface PiRegisterToolHttpRequest {
  url: string;
  method: PiRegisterToolHttpPolicy["methods"][number];
  headers: Record<string, string>;
  body: string | null;
  credentialSlot: string | null;
}

export interface PiRegisterToolHttpResponse {
  status: number;
  ok: boolean;
  url: string;
  headers: Record<string, string>;
  body: string;
  bodyEncoding: "utf8" | "base64";
  redirected: boolean;
}

export interface PiRegisterToolHttpAudit {
  method: PiRegisterToolHttpRequest["method"];
  origin: string;
  status: number;
  responseBytes: number;
  durationMillis: number;
  redirects: number;
}

export interface PiRegisterToolHttpExecution {
  response: PiRegisterToolHttpResponse;
  audit: PiRegisterToolHttpAudit;
}

interface PiRegisterToolActivityIdentity {
  toolCallId: string;
  seq: number;
  packageId: string;
}

export type PiRegisterToolActivity = PiRegisterToolActivityIdentity & (
  | {
      kind: "host_tool";
      phase: "started" | "completed" | "failed";
      name: string;
      targetTool: string;
      code?: string;
    }
  | ({
      kind: "https";
      phase: "started" | "completed" | "failed";
      code?: string;
    } & Partial<PiRegisterToolHttpAudit>)
);

export interface PiRegisterToolExtensionOptions {
  onActivity?: (activity: PiRegisterToolActivity) => void;
}

export function requirePiRegisterHttpPolicy(value: unknown): PiRegisterToolHttpPolicy {
  const record = requireRecord(value, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
  requireExactKeys(
    record,
    ["credentialSlots", "methods", "origins"],
    "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID",
  );
  const origins = requireArray(record.origins, 0, 8, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID")
    .map((origin) => requireHttpsOrigin(origin));
  requireUnique(origins, "PI_MOBILE_EXTENSION_V2_HTTP_ORIGIN_DUPLICATE");
  const allowedMethods = new Set(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]);
  const methods = requireArray(record.methods, 0, 6, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID")
    .map((method) => {
      const normalized = requireString(method, 3, 6, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
      if (!allowedMethods.has(normalized)) {
        throw new Error("PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
      }
      return normalized as PiRegisterToolHttpPolicy["methods"][number];
    });
  requireUnique(methods, "PI_MOBILE_EXTENSION_V2_HTTP_METHOD_DUPLICATE");
  const credentialSlots = requireArray(
    record.credentialSlots,
    0,
    8,
    "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID",
  ).map((value) => {
    const credential = requireRecord(value, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
    requireExactKeys(
      credential,
      ["origin", "placement", "slot"],
      "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID",
    );
    if (credential.placement !== "authorization_bearer") {
      throw new Error("PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
    }
    const origin = requireHttpsOrigin(credential.origin);
    if (!origins.includes(origin)) throw new Error("PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
    return {
      slot: requirePatternString(
        credential.slot,
        /^[a-z][a-z0-9._-]{0,63}$/,
        64,
        "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID",
      ),
      origin,
      placement: "authorization_bearer" as const,
    };
  });
  requireUnique(
    credentialSlots.map((slot) => slot.slot),
    "PI_MOBILE_EXTENSION_V2_HTTP_SLOT_DUPLICATE",
  );
  return { origins, methods, credentialSlots };
}

export function requirePiRegisterHttpRequest(
  value: unknown,
  policy: PiRegisterToolHttpPolicy,
): PiRegisterToolHttpRequest {
  const request = requireRecord(value, "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  requireExactKeys(
    request,
    ["body", "credentialSlot", "headers", "method", "url"],
    "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID",
  );
  const url = requireString(request.url, 9, 2_048, "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  const origin = piRegisterHttpOrigin(url);
  if (!policy.origins.includes(origin)) throw new Error("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED");
  const method = requireString(
    request.method,
    3,
    6,
    "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID",
  ) as PiRegisterToolHttpRequest["method"];
  if (!policy.methods.includes(method)) {
    throw new Error("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED");
  }
  const rawHeaders = requireRecord(request.headers, "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  if (Object.keys(rawHeaders).length > 32) throw new Error("PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  const headers: Record<string, string> = {};
  for (const [name, rawValue] of Object.entries(rawHeaders)) {
    if (!/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$/.test(name)) {
      throw new Error("PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
    }
    headers[name] = requireString(rawValue, 0, 4_096, "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  }
  if (jsonByteLength(headers) > 8_192) throw new Error("PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  const body = request.body === null
    ? null
    : requireString(request.body, 0, 262_144, "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  if (body !== null && jsonByteLength(body) > 262_146) {
    throw new Error("PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  }
  if ((method === "GET" || method === "HEAD") && body !== null) {
    throw new Error("PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID");
  }
  const credentialSlot = request.credentialSlot === null
    ? null
    : requirePatternString(
        request.credentialSlot,
        /^[a-z][a-z0-9._-]{0,63}$/,
        64,
        "PI_MOBILE_EXTENSION_HTTP_REQUEST_INVALID",
      );
  if (credentialSlot !== null && !policy.credentialSlots.some(
    (slot) => slot.slot === credentialSlot && slot.origin === origin,
  )) {
    throw new Error("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_REQUIRED");
  }
  return { url, method, headers, body, credentialSlot };
}

export function requirePiRegisterHttpExecution(
  value: unknown,
  request: PiRegisterToolHttpRequest,
  policy: PiRegisterToolHttpPolicy,
): PiRegisterToolHttpExecution {
  const execution = requireRecord(value, PROTOCOL_ERROR);
  requireExactKeys(execution, ["audit", "response"], PROTOCOL_ERROR);
  const responseValue = requireRecord(execution.response, PROTOCOL_ERROR);
  requireExactKeys(
    responseValue,
    ["body", "bodyEncoding", "headers", "ok", "redirected", "status", "url"],
    PROTOCOL_ERROR,
  );
  const status = requireInteger(responseValue.status, 100, 599, PROTOCOL_ERROR);
  const responseHeaders = requireRecord(responseValue.headers, PROTOCOL_ERROR);
  const safeHeaders = new Set([
    "cache-control", "content-language", "content-length", "content-type", "etag", "expires",
    "last-modified",
  ]);
  if (Object.entries(responseHeaders).some(([name, header]) =>
    !safeHeaders.has(name) || typeof header !== "string"
  ) || jsonByteLength(responseHeaders) > 8_192) {
    throw new Error(PROTOCOL_ERROR);
  }
  if (responseValue.bodyEncoding !== "utf8" && responseValue.bodyEncoding !== "base64") {
    throw new Error(PROTOCOL_ERROR);
  }
  const response: PiRegisterToolHttpResponse = {
    status,
    ok: responseValue.ok === true,
    url: requireString(responseValue.url, 9, 2_048, PROTOCOL_ERROR),
    headers: responseHeaders as Record<string, string>,
    body: requireString(responseValue.body, 0, 1_398_104, PROTOCOL_ERROR),
    bodyEncoding: responseValue.bodyEncoding,
    redirected: responseValue.redirected === true,
  };
  if (response.bodyEncoding === "base64") {
    requireBoundedBase64(response.body);
  }
  if (!policy.origins.includes(piRegisterHttpOrigin(response.url)) ||
      response.ok !== (status >= 200 && status < 300) ||
      typeof responseValue.ok !== "boolean" || typeof responseValue.redirected !== "boolean") {
    throw new Error(PROTOCOL_ERROR);
  }
  const auditValue = requireRecord(execution.audit, PROTOCOL_ERROR);
  requireExactKeys(
    auditValue,
    ["durationMillis", "method", "origin", "redirects", "responseBytes", "status"],
    PROTOCOL_ERROR,
  );
  const audit: PiRegisterToolHttpAudit = {
    method: requireEqual(auditValue.method, request.method),
    origin: requireEqual(auditValue.origin, piRegisterHttpOrigin(request.url)),
    status: requireEqual(auditValue.status, status),
    responseBytes: requireInteger(auditValue.responseBytes, 0, 1_048_576, PROTOCOL_ERROR),
    durationMillis: requireInteger(auditValue.durationMillis, 0, 60_000, PROTOCOL_ERROR),
    redirects: requireInteger(auditValue.redirects, 0, 3, PROTOCOL_ERROR),
  };
  return { response, audit };
}

export function piRegisterHttpOrigin(url: string): string {
  const match = /^https:\/\/([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(?:\/|\?|$)/.exec(url);
  if (match === null || url.includes("@") || url.includes("#")) {
    throw new Error("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED");
  }
  return canonicalOrigin(match[1]!, match[2], "EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED");
}

export function emitPiRegisterToolActivity(
  options: PiRegisterToolExtensionOptions,
  activity: PiRegisterToolActivity,
): void {
  try {
    options.onActivity?.(activity);
  } catch {
    // Product telemetry and Tool Activity rendering cannot change execution truth.
  }
}

function requireHttpsOrigin(value: unknown): string {
  const origin = requireString(value, 9, 256, "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
  const match = /^https:\/\/([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$/.exec(origin);
  if (match === null || origin.includes("@") || origin.includes("#") ||
      canonicalOrigin(match[1]!, match[2], "PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID") !== origin) {
    throw new Error("PI_MOBILE_EXTENSION_V2_HTTP_POLICY_INVALID");
  }
  return origin;
}

function canonicalOrigin(host: string, rawPort: string | undefined, code: string): string {
  const normalizedHost = host.toLowerCase();
  const port = rawPort === undefined ? 443 : Number(rawPort);
  if (normalizedHost.length > 253 || normalizedHost.startsWith(".") ||
      normalizedHost.endsWith(".") || normalizedHost.includes("..") ||
      /^[0-9]+(?:\.[0-9]+){3}$/.test(normalizedHost) ||
      !Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error(code);
  }
  return `https://${normalizedHost}${port === 443 ? "" : `:${port}`}`;
}

function requireBoundedBase64(value: string): void {
  if (value.length % 4 !== 0 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new Error(PROTOCOL_ERROR);
  }
  const padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
  if ((value.length / 4) * 3 - padding > 1_048_576) throw new Error(PROTOCOL_ERROR);
}

function requireRecord(value: unknown, code: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(code);
  return value as Record<string, unknown>;
}

function requireArray(value: unknown, minimum: number, maximum: number, code: string): unknown[] {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
    throw new Error(code);
  }
  return value;
}

function requireString(value: unknown, minimum: number, maximum: number, code: string): string {
  if (typeof value !== "string" || value.length < minimum || value.length > maximum ||
      value.includes("\0")) {
    throw new Error(code);
  }
  return value;
}

function requirePatternString(value: unknown, pattern: RegExp, maximum: number, code: string): string {
  const text = requireString(value, 1, maximum, code);
  if (!pattern.test(text)) throw new Error(code);
  return text;
}

function requireInteger(value: unknown, minimum: number, maximum: number, code: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(code);
  }
  return value;
}

function requireExactKeys(record: Record<string, unknown>, expected: string[], code: string): void {
  const actual = Object.keys(record).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(code);
  }
}

function requireUnique(values: string[], code: string): void {
  if (new Set(values).size !== values.length) throw new Error(code);
}

function requireEqual<T>(value: unknown, expected: T): T {
  if (value !== expected) throw new Error(PROTOCOL_ERROR);
  return expected;
}

function jsonByteLength(value: unknown): number {
  const encoded = JSON.stringify(value);
  if (encoded === undefined) return Number.POSITIVE_INFINITY;
  let bytes = 0;
  for (let index = 0; index < encoded.length; index += 1) {
    const code = encoded.charCodeAt(index);
    if (code <= 0x7f) bytes += 1;
    else if (code <= 0x7ff) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff &&
        encoded.charCodeAt(index + 1) >= 0xdc00 && encoded.charCodeAt(index + 1) <= 0xdfff) {
      bytes += 4;
      index += 1;
    } else bytes += 3;
  }
  return bytes;
}

const PROTOCOL_ERROR = "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH";
