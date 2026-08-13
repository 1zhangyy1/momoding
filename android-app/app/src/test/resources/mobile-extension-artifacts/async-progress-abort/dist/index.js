// index.ts
async function asyncProgressAbort(pi) {
  await Promise.resolve();
  pi.registerTool({
    name: "fixture_progress",
    label: "Fixture progress",
    description: "Emit bounded progress and honor cancellation.",
    parameters: { "additionalProperties": false, "type": "object", "properties": {} },
    async execute(_toolCallId, _params, signal, onUpdate) {
      let aborted = signal?.aborted ?? false;
      signal?.addEventListener("abort", () => {
        aborted = true;
      }, { once: true });
      onUpdate?.({
        content: [{ type: "text", text: "Working" }],
        details: { step: 1 }
      });
      await Promise.resolve();
      if (aborted || signal?.aborted)
        throw new Error("EXTENSION_PACKAGE_STOPPED");
      return {
        content: [{ type: "text", text: "Done" }],
        details: { step: 2 }
      };
    }
  });
}
export {
  asyncProgressAbort as default
};
