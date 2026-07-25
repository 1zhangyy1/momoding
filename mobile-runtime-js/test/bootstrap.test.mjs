import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";
import { webcrypto } from "node:crypto";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

test("bundle creates, inspects, and closes the real Pi AgentHarness", async () => {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
  });

  runInContext(bundle, context, { filename: "pi-mobile.js" });
  const bootstrap = JSON.parse(runInContext("PiMobileRuntimeBundle.bootstrapJson()", context));
  assert.equal(bootstrap.ok, true);
  assert.equal(bootstrap.piVersion, "0.80.6");
  assert.equal(bootstrap.runtime, "AgentHarness");
  assert.equal(bootstrap.modelId, "phone-local-bootstrap");
  assert.equal(bootstrap.capabilities.secureRandom, true);

  const status = JSON.parse(runInContext("PiMobileRuntimeBundle.statusJson()", context));
  assert.equal(status.booted, true);
  assert.equal(status.runtime, "AgentHarness");

  const closed = JSON.parse(runInContext("PiMobileRuntimeBundle.closeJson()", context));
  assert.deepEqual(closed, { ok: true, closed: true });
  const afterClose = JSON.parse(runInContext("PiMobileRuntimeBundle.statusJson()", context));
  assert.equal(afterClose.booted, false);
});
