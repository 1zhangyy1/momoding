// index.ts
function officialRegisterTool(pi) {
  pi.registerTool({
    name: "fixture_echo",
    label: "Fixture echo",
    description: "Return one bounded text value.",
    parameters: { "additionalProperties": false, "type": "object", "properties": { "text": { "maxLength": 160, "type": "string" } }, "required": ["text"] },
    async execute(_toolCallId, params) {
      return {
        content: [{ type: "text", text: String(params.text) }],
        details: { echoed: true }
      };
    }
  });
}
export {
  officialRegisterTool as default
};
