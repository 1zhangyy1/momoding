// index.ts
import { deleteState, getState, setState } from "@momoding/sdk";
function stateFixture(pi) {
  pi.registerTool({
    name: "fixture_counter",
    label: "Fixture counter",
    description: "Increment package-scoped bounded state.",
    parameters: { "additionalProperties": false, "type": "object", "properties": { "reset": { "type": "boolean" } } },
    async execute(_toolCallId, params) {
      if (params.reset === true)
        await deleteState("count");
      const previous = Number(await getState("count") ?? 0);
      const count = previous + 1;
      await setState("count", count);
      return {
        content: [{ type: "text", text: `Count: ${count}` }],
        details: { count }
      };
    }
  });
}
export {
  stateFixture as default
};
