import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

test("schema v2 Pi registerTool driver preserves metadata and step/resume semantics", async () => {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
    AbortController,
    clearTimeout,
    setTimeout,
  });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  const result = JSON.parse(await runInContext(
    "PiMobileRuntimeBundle.piRegisterToolExtensionContractJson()",
    context,
  ));

  assert.deepEqual(result.tool, {
    name: "fixture_calendar_summary",
    label: "Fixture calendar summary",
    description: "Read capabilities and calendar through declared Host tools.",
    parameters: {
      type: "object",
      properties: { query: { type: "string" } },
      required: ["query"],
      additionalProperties: false,
    },
    executionMode: "sequential",
    promptSnippet: null,
    promptGuidelines: [],
  });
  assert.deepEqual(result.updates, [
    { content: [{ type: "text", text: "Preparing" }], details: { step: 1 } },
    { content: [{ type: "text", text: "Checking" }], details: { step: 2 } },
  ]);
  assert.deepEqual(result.result, {
    content: [{ type: "text", text: "Two host calls completed" }],
    details: { count: 2 },
  });
  assert.deepEqual(result.trace, [
    "device_capabilities_get:outer-1:extension:2",
    "device_calendar:outer-1:extension:3",
  ]);
  assert.deepEqual(result.activity, [
    {
      kind: "host_tool",
      phase: "started",
      toolCallId: "outer-1",
      seq: 2,
      packageId: "fixtures.host-call",
      name: "capabilities",
      targetTool: "device_capabilities_get",
    },
    {
      kind: "host_tool",
      phase: "completed",
      toolCallId: "outer-1",
      seq: 2,
      packageId: "fixtures.host-call",
      name: "capabilities",
      targetTool: "device_capabilities_get",
    },
    {
      kind: "host_tool",
      phase: "started",
      toolCallId: "outer-1",
      seq: 3,
      packageId: "fixtures.host-call",
      name: "calendar",
      targetTool: "device_calendar",
    },
    {
      kind: "host_tool",
      phase: "completed",
      toolCallId: "outer-1",
      seq: 3,
      packageId: "fixtures.host-call",
      name: "calendar",
      targetTool: "device_calendar",
    },
  ]);
  assert.deepEqual(result.transportTrace, [
    "start",
    "next:0",
    "next:1",
    "authorize:2:capabilities",
    "resume:2:text",
    "authorize:3:calendar",
    "resume:3:text",
  ]);
  assert.match(result.protocolError, /EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH/);
  assert.equal(result.protocolCancelled, true);
  assert.deepEqual(result.httpResult, {
    content: [{ type: "text", text: "Status fetched" }],
    details: { ok: true },
  });
  assert.deepEqual(result.httpTrace, ["start", "http", "resume:0:result"]);
  assert.deepEqual(result.httpActivity, [
    {
      kind: "https",
      phase: "started",
      toolCallId: "outer-3",
      seq: 0,
      packageId: "fixtures.host-call",
      method: "GET",
      origin: "https://status.example.test",
    },
    {
      kind: "https",
      phase: "completed",
      toolCallId: "outer-3",
      seq: 0,
      packageId: "fixtures.host-call",
      method: "GET",
      origin: "https://status.example.test",
      status: 200,
      responseBytes: 15,
      durationMillis: 8,
      redirects: 0,
    },
  ]);
  assert.deepEqual(result.httpTimeoutError, {
    message: "EXTENSION_PACKAGE_HOST_TIMEOUT",
    typed: true,
    partialEffects: true,
  });
  assert.equal(result.httpTimeoutAbortSeen, true);
  assert.equal(result.httpTimeoutResumeError, "EXTENSION_PACKAGE_HOST_TIMEOUT");
  assert.deepEqual(result.workerError, {
    message: "EXTENSION_PACKAGE_FAILED",
    typed: true,
    partialEffects: true,
  });
  assert.deepEqual(result.transportAfterEffectError, {
    message: "EXTENSION_PACKAGE_WORKER_UNAVAILABLE",
    typed: true,
    partialEffects: true,
  });
  assert.deepEqual(result.invalidArgumentsError, {
    message: "EXTENSION_PACKAGE_HOST_CALL_FAILED",
    typed: true,
    partialEffects: false,
  });
  assert.equal(result.invalidResumeError, "PI_MOBILE_EXTENSION_HOST_CALL_FAILED");
  assert.equal(result.invalidTargetCalls, 0);
  assert.match(result.runtimeError, /PI_MOBILE_EXTENSION_V2_RUNTIME_UNSUPPORTED/);
  assert.match(result.stopError, /EXTENSION_PACKAGE_STOPPED/);
  assert.equal(result.cancelled, true);
  assert.deepEqual(result.timeoutError, {
    message: "EXTENSION_PACKAGE_HOST_TIMEOUT",
    typed: true,
    partialEffects: true,
  });
  assert.equal(result.timeoutAbortSeen, true);
  assert.equal(result.timeoutResumeError, "EXTENSION_PACKAGE_HOST_TIMEOUT");
  assert.equal(result.timeoutLateUpdateCount, 0);
  assert.deepEqual(result.stopHostError, {
    message: "EXTENSION_PACKAGE_STOPPED",
    typed: true,
    partialEffects: true,
  });
  assert.equal(result.stopHostAbortSeen, true);
  assert.equal(result.stopHostCancelled, true);
  assert.equal(result.stopHostLateUpdateCount, 0);
  assert.deepEqual(result.revokedHostError, {
    message: "EXTENSION_PACKAGE_NOT_ENABLED",
    typed: true,
    partialEffects: false,
  });
  assert.equal(result.revokedResumeError, "EXTENSION_PACKAGE_NOT_ENABLED");
  assert.equal(result.revokedTargetCalls, 0);
});
