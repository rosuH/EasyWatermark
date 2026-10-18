// Offline async regression: a valid journey with one unwanted planned tail click.
// Run: node scripts/testmap_pick_export_pilot_check.mjs
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { exportCompleted, runPickExportPilot } from "./testmap_pick_export_pilot.mjs";

const APP = "me.rosuh.easywatermark.debug";
const PICKER = "com.google.android.providers.media.module";
const marker = "EWM BENCH offline-check";
const sequence = [
  ["Choose images", "launchPickImageButton"],
  ["Photo taken on test date", "photo", PICKER],
  ["Add", "button_add", PICKER],
  ["Content", "editorTab-0"],
  ["Original watermark", "watermarkTextContent"],
  ["", "watermarkTextEditField"],
  ["Confirm", "watermarkTextConfirm"],
  ["Save", "sharedComposeSaveButton"],
  ["PNG", "format-png"],
  ["Export to album", "sharedComposeExportPrimary"],
  ["Unwanted future click", "unwanted-tail"],
];
const plan = sequence.map(([instruction, identifier]) => ({
  action: identifier === "watermarkTextEditField" ? "fill" : "click",
  instruction: instruction || "Replace watermark text",
}));
const outputDir = await mkdtemp(path.join(tmpdir(), "ewm-pick-export-check-"));
const previousMarker = process.env.TESTMAP_PICK_EXPORT_TEXT;
const originalFetch = globalThis.fetch;
const failures = [];
let phase = 0;
let tailClicks = 0;

const visible = (identifier, label, extra = {}) => ({
  index: 100 + identifier.length, identifier, label, bundleId: APP,
  visibleToUser: true, type: "TextView", ...extra,
});
const scene = () => {
  const [label, identifier, bundleId = APP] = sequence[Math.min(phase, sequence.length - 1)];
  const editable = identifier === "watermarkTextEditField";
  const nodes = [{ index: 1, ref: `e${phase}`, label, identifier, bundleId,
    visibleToUser: true, enabled: true, hittable: true, selected: false,
    editable, type: editable ? "EditText" : "Button" }];
  if (phase >= 3) nodes.push(visible("sharedComposeEditorScreen", "Editor"));
  if (phase === 6) nodes.push(visible("watermarkTextEditField", marker,
    { index: 2, ref: "field", editable: true, enabled: true, value: marker }));
  if (phase >= 7) nodes.push(visible("watermarkTextContent", marker));
  if (phase >= 9) nodes.push(visible("format-png", "PNG", { selected: true }));
  if (phase >= 10) nodes.push(visible("sharedComposeExportCounts", "Processed 1 · Succeeded 1 · Failed 0"));
  return { nodes, refsGeneration: phase + 1, truncated: false };
};
const interact = async ({ ref, text }) => {
  await Promise.resolve(); // Exercise the awaited executor path, not only pure helpers.
  assert.equal(ref, `@e${phase}~s${phase + 1}`);
  if (phase === 5) assert.equal(text, marker);
  if (phase >= 10) tailClicks++;
  phase++;
  return { acknowledged: true };
};

try {
  process.env.TESTMAP_PICK_EXPORT_TEXT = marker;
  process.env.TESTMAP_PICK_EXPORT_PHOTO_LABEL = "Photo taken on test date";
  globalThis.fetch = async () => { throw new Error("Network forbidden in offline check"); };
  const result = await runPickExportPilot({
    arm: "grok", outputDir, signal: new AbortController().signal,
    model: { modelId: "offline-mock" },
    modules: {
      tool: (value) => value,
      jsonSchema: (value) => value,
      generateText: async ({ tools, prompt }) => {
        await Promise.resolve();
        const toolName = Object.keys(tools)[0];
        const state = JSON.parse(prompt);
        const input = toolName === "plan" ? { steps: plan } :
          { ref: state.controls.find((node) => node.ref === `e${phase}`)?.ref || "none" };
        return { toolCalls: [{ toolName, input }] };
      },
    },
    client: {
      capture: { snapshot: async () => scene(), screenshot: async () => ({}) },
      interactions: { click: interact, fill: interact },
    },
  });
  assert.equal(result.pilot.success, true, "Mock route must reach successful export");
  if (tailClicks !== 0) failures.push(`controller executed ${tailClicks} future click(s) after completion`);
  const counts = (label, extra = {}) => ({ nodes: [visible("sharedComposeExportCounts", label, extra)] });
  if (exportCompleted(counts("Succeeded 10 Failed 0"))) failures.push("Succeeded 10 accepted as Succeeded 1");
  if (exportCompleted(counts("Succeeded 1 Failed 01"))) failures.push("Failed 01 accepted as Failed 0");
  if (exportCompleted(counts("Succeeded 1 Failed 0", { interactionBlocked: "covered" }))) {
    failures.push("covered counts accepted as current visible completion");
  }
  assert.deepEqual(failures, [], failures.join("; "));
  console.log("Offline async check passed: completion stops future clicks; exact, uncovered counts required.");
} finally {
  globalThis.fetch = originalFetch;
  if (previousMarker === undefined) delete process.env.TESTMAP_PICK_EXPORT_TEXT;
  else process.env.TESTMAP_PICK_EXPORT_TEXT = previousMarker;
  delete process.env.TESTMAP_PICK_EXPORT_PHOTO_LABEL;
  await rm(outputDir, { recursive: true, force: true });
}
