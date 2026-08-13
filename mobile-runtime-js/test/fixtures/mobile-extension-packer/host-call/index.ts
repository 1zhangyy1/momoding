import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { callTool } from "@momoding/sdk";

export default function hostCallFixture(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "fixture_calendar_summary",
    label: "Fixture calendar summary",
    description: "Read capabilities and then query the calendar through declared Host tools.",
    parameters: Type.Object({
      query: Type.String({ maxLength: 120 }),
    }, { additionalProperties: false }),
    async execute(_toolCallId, params) {
      const capabilities = await callTool("capabilities", {});
      const calendar = await callTool("calendar", {
        action: "list_calendars",
        purpose: params.query,
      });
      return {
        content: [{ type: "text", text: "Calendar query completed." }],
        details: { capabilities, calendar },
      };
    },
  });
}
