package app.momoding.core.extensions

internal fun piExtensionWorkerPrelude(expectedJson: String): String = """
    (() => {
      "use strict";
      const host = globalThis.__momodingWorkerHost;
      const expected = $expectedJson;
      const handlers = new Map();
      let registrationOpen = true;
      let aborted = false;
      const abortListeners = new Set();
      const signal = Object.freeze({
        get aborted() { return aborted || host.isAborted(); },
        addEventListener(type, listener) {
          if (type === "abort" && typeof listener === "function") abortListeners.add(listener);
        },
        removeEventListener(type, listener) {
          if (type === "abort") abortListeners.delete(listener);
        },
      });
      const abort = () => {
        if (aborted) return;
        aborted = true;
        let firstError;
        let dispatched = 0;
        for (const listener of [...abortListeners]) {
          try {
            listener.call(signal, { type: "abort", target: signal });
          } catch (error) {
            if (firstError === undefined) firstError = error;
          } finally {
            dispatched += 1;
          }
        }
        abortListeners.clear();
        host.recordAbortListenersDispatched(dispatched);
        if (firstError !== undefined) throw firstError;
      };
      const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
      const stable = (value) => {
        if (Array.isArray(value)) return value.map(stable);
        if (!isObject(value)) return value;
        const result = {};
        for (const key of Object.keys(value).sort()) result[key] = stable(value[key]);
        return result;
      };
      const decodeBase64 = (value) => {
        const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        if (typeof value !== "string" || value.length % 4 !== 0 ||
            /[^A-Za-z0-9+/=]/.test(value)) {
          throw new Error("MOMODING_EXTENSION_HTTP_RESPONSE_INVALID");
        }
        const bytes = [];
        for (let index = 0; index < value.length; index += 4) {
          const a = alphabet.indexOf(value[index]);
          const b = alphabet.indexOf(value[index + 1]);
          const c = value[index + 2] === "=" ? 0 : alphabet.indexOf(value[index + 2]);
          const d = value[index + 3] === "=" ? 0 : alphabet.indexOf(value[index + 3]);
          if (a < 0 || b < 0 || c < 0 || d < 0) {
            throw new Error("MOMODING_EXTENSION_HTTP_RESPONSE_INVALID");
          }
          bytes.push((a << 2) | (b >> 4));
          if (value[index + 2] !== "=") bytes.push(((b & 15) << 4) | (c >> 2));
          if (value[index + 3] !== "=") bytes.push(((c & 3) << 6) | d);
        }
        return new Uint8Array(bytes);
      };
      const boundedFetch = async (input, init = {}) => {
        if (typeof input !== "string" || !isObject(init)) {
          throw new Error("MOMODING_EXTENSION_HTTP_REQUEST_INVALID");
        }
        const allowed = new Set(["method", "headers", "body", "credentialSlot"]);
        if (Object.keys(init).some((key) => !allowed.has(key))) {
          throw new Error("MOMODING_EXTENSION_HTTP_REQUEST_INVALID");
        }
        const headers = init.headers ?? {};
        if (!isObject(headers) || Object.values(headers).some((value) => typeof value !== "string")) {
          throw new Error("MOMODING_EXTENSION_HTTP_REQUEST_INVALID");
        }
        const request = {
          url: input,
          method: init.method ?? "GET",
          headers,
          body: init.body ?? null,
          credentialSlot: init.credentialSlot ?? null,
        };
        const envelope = JSON.parse(await host.fetchJson(JSON.stringify(request)));
        if (envelope.ok !== true) {
          if (envelope.errorCode === "EXTENSION_PACKAGE_STOPPED" ||
              envelope.errorCode === "EXTENSION_PACKAGE_HOST_TIMEOUT") abort();
          throw new Error(envelope.errorCode || "EXTENSION_PACKAGE_HTTP_FAILED");
        }
        const response = envelope.result;
        if (!isObject(response) || !isObject(response.headers)) {
          throw new Error("MOMODING_EXTENSION_HTTP_RESPONSE_INVALID");
        }
        const headerValues = Object.freeze({ ...response.headers });
        const text = async () => {
          if (response.bodyEncoding !== "utf8") {
            throw new Error("MOMODING_EXTENSION_HTTP_BINARY_BODY");
          }
          return response.body;
        };
        return Object.freeze({
          status: response.status,
          ok: response.ok,
          url: response.url,
          redirected: response.redirected,
          headers: Object.freeze({
            get: (name) => headerValues[String(name).toLowerCase()] ?? null,
            has: (name) => Object.prototype.hasOwnProperty.call(
              headerValues,
              String(name).toLowerCase(),
            ),
            entries: () => Object.entries(headerValues)[Symbol.iterator](),
          }),
          text,
          json: async () => JSON.parse(await text()),
          arrayBuffer: async () => {
            if (response.bodyEncoding !== "base64") {
              throw new Error("MOMODING_EXTENSION_HTTP_TEXT_BODY");
            }
            return decodeBase64(response.body).buffer;
          },
        });
      };
      Object.defineProperty(globalThis, "fetch", {
        value: boundedFetch,
        writable: false,
        configurable: false,
      });
      const canonicalDefinition = (definition) => ({
        name: definition.name,
        label: definition.label,
        description: definition.description,
        parameters: definition.parameters,
        promptSnippet: definition.promptSnippet ?? null,
        promptGuidelines: definition.promptGuidelines ?? [],
        executionMode: definition.executionMode ?? "sequential",
      });
      const registerTool = (definition) => {
        const allowedKeys = new Set([
          "name", "label", "description", "parameters", "execute",
          "promptSnippet", "promptGuidelines", "executionMode",
        ]);
        if (!registrationOpen || !isObject(definition) ||
            Object.keys(definition).some((key) => !allowedKeys.has(key)) ||
            typeof definition.execute !== "function" || typeof definition.name !== "string" ||
            typeof definition.label !== "string" || typeof definition.description !== "string" ||
            !isObject(definition.parameters) ||
            (definition.promptSnippet !== undefined && definition.promptSnippet !== null &&
              typeof definition.promptSnippet !== "string") ||
            (definition.promptGuidelines !== undefined &&
              (!Array.isArray(definition.promptGuidelines) ||
                definition.promptGuidelines.some((value) => typeof value !== "string"))) ||
            (definition.executionMode !== undefined && definition.executionMode !== "sequential") ||
            handlers.has(definition.name)) {
          throw new Error("MOMODING_EXTENSION_REGISTRATION_INVALID");
        }
        handlers.set(definition.name, { definition: canonicalDefinition(definition), execute: definition.execute });
      };
      const loadFactory = async (factory) => {
        if (typeof factory !== "function") throw new Error("MOMODING_EXTENSION_FACTORY_INVALID");
        await factory(Object.freeze({ registerTool }));
        registrationOpen = false;
        const actual = [...handlers.values()].map((value) => value.definition);
        if (JSON.stringify(stable(actual)) !== JSON.stringify(stable(expected))) {
          throw new Error("MOMODING_EXTENSION_REGISTRATION_MISMATCH");
        }
      };
      const requireResult = (value) => {
        if (!isObject(value) || !Array.isArray(value.content) || value.content.length === 0 ||
            value.content.some((item) => !isObject(item) || item.type !== "text" || typeof item.text !== "string") ||
            !("details" in value)) {
          throw new Error("MOMODING_EXTENSION_RESULT_INVALID");
        }
        return value;
      };
      const executeJson = async (toolName, toolCallId, argumentsJson) => {
        registrationOpen = false;
        const handler = handlers.get(toolName)?.execute;
        if (handler === undefined) throw new Error("MOMODING_EXTENSION_TOOL_MISSING");
        const ctx = Object.freeze({ mode: "rpc", hasUI: false, cwd: "/extension", signal });
        const onUpdate = (update) => host.emitUpdateJson(JSON.stringify(requireResult(update)));
        const value = await handler(toolCallId, JSON.parse(argumentsJson), signal, onUpdate, ctx);
        if (signal.aborted) throw new Error("EXTENSION_PACKAGE_STOPPED");
        host.checkpointExecution();
        return JSON.stringify(requireResult(value));
      };
      Object.defineProperty(globalThis, "__momodingPiWorker", {
        value: Object.freeze({ registerTool, loadFactory, executeJson, abort }),
        writable: false,
        configurable: false,
      });
    })();
""".trimIndent()
