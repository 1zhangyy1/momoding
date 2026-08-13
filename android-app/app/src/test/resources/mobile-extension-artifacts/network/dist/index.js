// index.ts
function networkFixture(pi) {
  pi.registerTool({
    name: "fixture_status",
    label: "Fixture status",
    description: "Fetch bounded JSON from one declared HTTPS origin.",
    parameters: { "additionalProperties": false, "type": "object", "properties": {} },
    async execute() {
      const response = await fetch("https://status.example.test/v1/status", {
        method: "GET",
        headers: { accept: "application/json" }
      });
      if (!response.ok)
        throw new Error(`HTTP_${response.status}`);
      const status = await response.json();
      return {
        content: [{ type: "text", text: "Status fetched." }],
        details: { status }
      };
    }
  });
}
export {
  networkFixture as default
};
