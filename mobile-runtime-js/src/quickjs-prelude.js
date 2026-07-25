(function installQuickJsPrelude(global) {
  if (typeof Object.hasOwn !== "function") {
    Object.hasOwn = function hasOwn(object, property) {
      return Object.prototype.hasOwnProperty.call(object, property);
    };
  }

  if (typeof global.structuredClone !== "function") {
    global.structuredClone = function structuredClone(value) {
      return JSON.parse(JSON.stringify(value));
    };
  }

  if (typeof global.queueMicrotask !== "function") {
    global.queueMicrotask = function queueMicrotask(callback) {
      Promise.resolve().then(callback);
    };
  }

  if (typeof global.TextEncoder !== "function") {
    global.TextEncoder = function TextEncoder() {};
    global.TextEncoder.prototype.encoding = "utf-8";
    global.TextEncoder.prototype.encode = function encode(input) {
      var encoded = unescape(encodeURIComponent(input || ""));
      var bytes = new Uint8Array(encoded.length);
      for (var index = 0; index < encoded.length; index += 1) {
        bytes[index] = encoded.charCodeAt(index);
      }
      return bytes;
    };
  }

  if (typeof global.TextDecoder !== "function") {
    global.TextDecoder = function TextDecoder() {};
    global.TextDecoder.prototype.encoding = "utf-8";
    global.TextDecoder.prototype.decode = function decode(input) {
      var view = input instanceof ArrayBuffer
        ? new Uint8Array(input)
        : new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
      var encoded = "";
      for (var index = 0; index < view.length; index += 1) {
        encoded += String.fromCharCode(view[index]);
      }
      return decodeURIComponent(escape(encoded));
    };
  }

  if (typeof global.AbortController !== "function") {
    function AbortSignal() {
      this.aborted = false;
      this.reason = undefined;
      this.onabort = null;
      this.listeners = [];
    }
    AbortSignal.prototype.addEventListener = function addEventListener(type, listener) {
      if (type === "abort") this.listeners.push(listener);
    };
    AbortSignal.prototype.removeEventListener = function removeEventListener(type, listener) {
      if (type !== "abort") return;
      this.listeners = this.listeners.filter(function keep(candidate) {
        return candidate !== listener;
      });
    };
    AbortSignal.prototype.throwIfAborted = function throwIfAborted() {
      if (this.aborted) throw this.reason || new Error("Operation aborted");
    };

    global.AbortController = function AbortController() {
      this.signal = new AbortSignal();
    };
    global.AbortController.prototype.abort = function abort(reason) {
      var signal = this.signal;
      if (signal.aborted) return;
      signal.aborted = true;
      signal.reason = reason || new Error("Operation aborted");
      var event = { type: "abort", target: signal };
      if (signal.onabort) signal.onabort(event);
      signal.listeners.slice().forEach(function notify(listener) {
        listener(event);
      });
    };
  }
})(globalThis);
