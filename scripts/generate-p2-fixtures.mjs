#!/usr/bin/env node

import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { projectP2Fixtures } from "./lib/p2-fixture-projection.mjs";

const [sourceArgument, outputArgument] = process.argv.slice(2);
if (!sourceArgument || !outputArgument) {
  throw new Error("usage: generate-p2-fixtures.mjs <source.json> <output.json>");
}

const sourcePath = resolve(sourceArgument);
const outputPath = resolve(outputArgument);
const source = JSON.parse(await readFile(sourcePath, "utf8"));
const projected = projectP2Fixtures(source);
await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(projected, null, 2)}\n`, "utf8");
process.stdout.write(
  `P2 fixtures: ${projected.fixtureCounts.total} total; ` +
    `${projected.fixtureCounts.active}/${projected.fixtureCounts.unavailablePlaceholder}/` +
    `${projected.fixtureCounts.laterPhaseOnly}; ${projected.goldenCaptures.length} captures; ` +
    `P2_5 ${projected.stepProfiles.P2_5.fixtureCounts.S1}+` +
    `${projected.stepProfiles.P2_5.fixtureCounts.S2} fixtures and ` +
    `${projected.stepProfiles.P2_5.captureCandidates.length} candidates; ` +
    `P2_6 ${projected.stepProfiles.P2_6.fixtureCounts.S3} fixtures and ` +
    `${projected.stepProfiles.P2_6.captureCandidates.length} candidates; ` +
    `P2_7 ${projected.stepProfiles.P2_7.fixtureCounts.S4} fixtures and ` +
    `${projected.stepProfiles.P2_7.captureCandidates.length} candidates\n`,
);
