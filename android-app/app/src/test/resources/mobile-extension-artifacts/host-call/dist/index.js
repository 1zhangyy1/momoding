// index.ts
import { callTool } from "@momoding/sdk";
function hostCallFixture(pi) {
  pi.registerTool({
    name: "fixture_calendar_summary",
    label: "Fixture calendar summary",
    description: "Read capabilities and then query the calendar through declared Host tools.",
    parameters: { "additionalProperties": false, "type": "object", "properties": { "query": { "maxLength": 120, "type": "string" } }, "required": ["query"] },
    async execute(_toolCallId, params) {
      const capabilities = await callTool("capabilities", {});
      const calendar = await callTool("calendar", {
        action: "list_calendars",
        purpose: params.query
      });
      return {
        content: [{ type: "text", text: "Calendar query completed." }],
        details: { capabilities, calendar }
      };
    }
  });
}
export {
  hostCallFixture as default
};
