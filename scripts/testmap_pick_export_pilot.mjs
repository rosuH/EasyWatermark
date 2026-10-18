// Bounded real-picker/text-edit/PNG-export comparison; no general agent executor.
import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { gateChoice, grokCall, jevCall, requireUnselected, targetSchema } from "./testmap_launch_about_pilot.mjs";

const APP = "me.rosuh.easywatermark.debug";
const PICKER = "com.google.android.providers.media.module";
const ALBUM = "EwmHybridBench";

export function exportCandidates(snapshot) {
  if (snapshot.truncated || snapshot.snapshotQuality?.state === "sparse") throw new Error("incomplete snapshot");
  const nodes = (snapshot.nodes || []).filter((n) => n.visibleToUser === true &&
    n.interactionBlocked !== "covered" && [APP, PICKER].includes(n.bundleId));
  const byIndex = new Map(nodes.map((n) => [n.index, n]));
  const ancestors = (n) => {
    const parents = [];
    while ((n = byIndex.get(n.parentIndex)) && !parents.includes(n)) parents.push(n);
    return parents;
  };
  const actions = nodes.filter((n) => n.ref && n.enabled === true && !n.password &&
    (n.editable || n.hittable || /(?:Button|Switch|CheckBox)$/.test(n.type || "")));
  return actions.filter((n) => !actions.some((c) => c !== n && ancestors(c).includes(n))).map((n) => {
    const descendants = nodes.filter((c) => ancestors(c).includes(n));
    const siblings = nodes.filter((c) => c.parentIndex === n.parentIndex && c.label);
    const labels = [...new Set(siblings.map((c) => c.label))];
    return { ref: n.ref, type: n.type, bundleId: n.bundleId, identifier: n.identifier,
      ancestorIdentifier: ancestors(n).find((p) => p.identifier)?.identifier,
      label: n.label || [...new Set(descendants.map((c) => c.label).filter(Boolean))].join(" | ") ||
        (labels.length === 1 ? labels[0] : undefined),
      selected: n.selected, editable: n.editable === true, value: n.value };
  });
}

const textOf = (s) => (s.nodes || []).filter((n) => n.visibleToUser && n.bundleId === APP)
  .map((n) => [n.label, n.value].filter(Boolean).join(" ")).join("\n");
const hasId = (s, id) => (s.nodes || []).some((n) => n.visibleToUser && n.bundleId === APP && n.identifier === id);
export function exportCompleted(snapshot) {
  const counts = (snapshot.nodes || []).filter((n) => n.visibleToUser === true && n.bundleId === APP &&
    n.interactionBlocked !== "covered" && n.identifier === "sharedComposeExportCounts");
  return !snapshot.truncated && counts.length === 1 && [counts[0].label, counts[0].value].some((text) =>
    /\bSucceeded\s+1(?!\d)/i.test(text || "") && /\bFailed\s+0(?!\d)/i.test(text || ""));
}

export function pngConfigured(snapshot) {
  const nodes = (snapshot.nodes || []).filter((n) => n.bundleId === APP && n.visibleToUser === true && n.interactionBlocked !== "covered");
  // Some installed Compose builds omit RadioButton selection in accessibility.
  // SaveExportOptionsSection enables quality only for JPEG; file oracle also decodes the PNG signature.
  return nodes.some((n) => n.label === "PNG") && (nodes.some((n) => n.label === "PNG" && n.selected === true) ||
    (hasId(snapshot, "sharedComposeExportSheet") && nodes.some((n) => n.identifier === "sliderTrack" && n.enabled === false)));
}

export function validateExportPlan(answer) {
  const steps = answer?.steps;
  if (!Array.isArray(steps) || steps.length < 8 || steps.length > 18 ||
    steps.some((s) => !["click", "fill"].includes(s?.action) ||
      !s.instruction?.trim() || s.instruction.length > 500) || steps.filter((s) => s.action === "fill").length !== 1) {
    throw new Error("plan must have 8-18 click/fill steps and exactly one text fill");
  }
  return steps;
}

export async function runPickExportPilot({ client, modules, model, arm, outputDir, signal, threshold = 0.9 }) {
  if (!["hybrid", "grok"].includes(arm)) throw new Error("pick-export supports hybrid or grok");
  const marker = process.env.TESTMAP_PICK_EXPORT_TEXT;
  if (!/^EWM BENCH [A-Za-z0-9 -]{1,80}$/.test(marker || "")) throw new Error("missing safe benchmark text");
  const photoLabel = process.env.TESTMAP_PICK_EXPORT_PHOTO_LABEL;
  if (!photoLabel?.startsWith("Photo taken on ")) throw new Error("missing unique fixture photo label");
  const sameLabel = (a, b) => a?.replace(/\s+/g, " ").trim() === b?.replace(/\s+/g, " ").trim();
  const goal = `Choose the system-picker photo with the exact accessibility label ${JSON.stringify(photoLabel)}, replace the watermark text with ${JSON.stringify(marker)}, confirm, then export one PNG to the album. Stop at Succeeded 1 / Failed 0.`;
  const trace = { arm, threshold, goal, steps: [], oracle: { sourceSelected: false,
    editorVisited: false, textFilled: false, textConfirmed: false, pngSelected: false, exportStarted: false, exportCompleted: false } };
  const started = performance.now();
  const save = async () => {
    trace.controllerMs = performance.now() - started;
    await writeFile(path.join(outputDir, "pilot-trace.json"), JSON.stringify(trace, null, 2) + "\n");
  };
  const snapshot = async (name) => {
    signal.throwIfAborted();
    const s = await client.capture.snapshot({ interactiveOnly: false, forceFull: true });
    exportCandidates(s); // Refuse incomplete evidence even during the final oracle.
    await writeFile(path.join(outputDir, `${name}.json`), JSON.stringify(s, null, 2) + "\n");
    return s;
  };
  try {
    trace.stage = "initial_oracle";
    let current = await snapshot("pilot-initial");
    if (!hasId(current, "launchPickImageButton")) throw new Error("expected Launch");
    trace.stage = "planning";
    trace.planner = {};
    trace.plan = validateExportPlan(await grokCall({ modules, model, signal, record: trace.planner,
      state: { goal, controls: exportCandidates(current),
        workflow: "Launch Choose images -> select the exact timestamp-labelled fixture photo -> Add -> Editor Content tab if needed -> text summary opens edit sheet -> fill editable field -> Confirm -> Save -> PNG -> Export to album. Content may already be selected. Text summary can directly open the sheet; do not click the Text heading. Do not click Share after export." },
      name: "plan", description: "Plan the full task as 8-18 single click/fill actions. Give semantic instructions only, no refs. One fill replaces the watermark. Include Choose Images, exact timestamp-labelled photo and Add as separate steps; do not use Albums. Include Content tab (host skips already-selected controls), text summary, fill, Confirm, Save, PNG and Export. No extra settings, back or OS actions.",
      schema: { type: "object", additionalProperties: false, properties: { steps: { type: "array", minItems: 8, maxItems: 18,
        items: { type: "object", additionalProperties: false, properties: {
          action: { type: "string", enum: ["click", "fill"] }, instruction: { type: "string", minLength: 1, maxLength: 500 } },
        required: ["action", "instruction"] } } }, required: ["steps"] } }));
    await save();
    for (const [index, planned] of trace.plan.entries()) {
      const step = { ...planned, acted: false };
      trace.steps.push(step);
      let controls = exportCandidates(current);
      if (planned.action === "fill") controls = controls.filter((n) => n.editable);
      if (!controls.length || controls.length > 254) throw new Error("invalid candidate count");
      step.candidates = controls;
      const state = { instruction: planned.instruction, action: planned.action, controls };
      let target;
      trace.stage = "provider";
      if (arm === "hybrid") {
        await jevCall({ state, candidates: controls, signal, record: step });
        trace.stage = "confidence_gate";
        try { target = gateChoice(step.answer, controls, threshold); step.gatePassed = true; step.selectedBy = "jev"; }
        catch (error) {
          if (error.code !== "LOW_CONFIDENCE") throw error;
          trace.stage = "handoff";
          current = await snapshot(`pilot-handoff-${index + 1}`);
          controls = exportCandidates(current).filter((n) => planned.action !== "fill" || n.editable);
          step.handoff = { reason: error.code };
          const answer = await grokCall({ modules, model, signal, record: step.handoff,
            state: { goal, plan: trace.plan, stepIndex: index, ...state, controls,
              jevAnswer: step.answer, snapshot: current.nodes }, name: "choose", schema: targetSchema(controls),
            description: "Choose the control for this planned action after Jev's low-confidence handoff; none stops safely." });
          target = controls.find((n) => n.ref === answer?.ref);
          step.selectedBy = "grok-handoff";
        }
      } else {
        const answer = await grokCall({ modules, model, signal, record: step, state,
          name: "choose", schema: targetSchema(controls), description: "Choose the control for this planned action, or none." });
        target = controls.find((n) => n.ref === answer?.ref);
        step.selectedBy = "grok";
      }
      if (!target) throw new Error("no valid control selected");
      trace.stage = "state_guard";
      const fresh = await snapshot(`pilot-before-${index + 1}`);
      const matches = exportCandidates(fresh).filter((n) => n.bundleId === target.bundleId && n.type === target.type &&
        n.identifier === target.identifier && n.ancestorIdentifier === target.ancestorIdentifier &&
        n.label === target.label && n.editable === target.editable);
      if (matches.length !== 1) throw new Error("target disappeared or became ambiguous");
      target = matches[0];
      step.executedTarget = target;
      step.nativeSelected = target.selected ?? null;
      const id = target.identifier || target.ancestorIdentifier || "";
      const isPhoto = target.bundleId === PICKER && /^Photo taken on/.test(target.label || "");
      if (isPhoto && (!sameLabel(target.label, photoLabel) || trace.oracle.sourceSelected ||
        exportCandidates(fresh).filter((n) => sameLabel(n.label, photoLabel)).length !== 1)) {
        throw new Error("photo must match the independently indexed unique fixture timestamp exactly");
      }
      if (id === "sharedComposeSaveButton" && !trace.oracle.textConfirmed) throw new Error("cannot save before verified edit");
      if (id === "sharedComposeExportPrimary" && (!trace.oracle.textConfirmed || !trace.oracle.pngSelected || trace.oracle.exportStarted)) {
        throw new Error("export requires confirmed text and PNG, exactly once");
      }
      const ref = fresh.refsGeneration === undefined ? target.ref : `@${target.ref.replace(/^@/, "")}~s${fresh.refsGeneration}`;
      if (planned.action === "fill") {
        if (!target.editable || !hasId(fresh, "watermarkTextEditField")) throw new Error("fill requires watermark editor field");
        step.actionArgs = { ref, text: marker, settle: true, timeoutMs: 10000 };
        step.actionResult = await client.interactions.fill(step.actionArgs);
        step.acted = true;
      } else if (target.selected === true && (id === "editorTab-0" || target.label === "PNG")) {
        step.skippedAlreadySelected = true;
      } else {
        requireUnselected(target);
        step.unselectedCheckPassed = true;
        step.actionArgs = { ref, settle: true, timeoutMs: 10000 };
        step.actionResult = await client.interactions.click(step.actionArgs);
        step.acted = true;
      }
      current = await snapshot(`pilot-after-${index + 1}`);
      if (isPhoto) trace.oracle.sourceSelected = true;
      if (hasId(current, "sharedComposeEditorScreen")) {
        if (!trace.oracle.sourceSelected) throw new Error("editor reached without verified fixture selection");
        trace.oracle.editorVisited = true;
      }
      if (planned.action === "fill") {
        trace.oracle.textFilled = (current.nodes || []).some((n) => n.editable && (n.value === marker || n.label === marker));
        if (!trace.oracle.textFilled) throw new Error("watermark fill readback mismatch");
      }
      if (id === "watermarkTextConfirm") {
        trace.oracle.textConfirmed = trace.oracle.textFilled && !hasId(current, "watermarkTextEditField") && textOf(current).includes(marker);
        if (!trace.oracle.textConfirmed) throw new Error("confirmed watermark not visible in editor");
      }
      if (target.label === "PNG") {
        trace.oracle.pngSelected = pngConfigured(current);
        step.pngEvidence = trace.oracle.pngSelected ? "PNG selected or JPEG-only quality control disabled; independent file signature required" : null;
        if (!trace.oracle.pngSelected) throw new Error("PNG selection not confirmed");
      }
      if (id === "sharedComposeExportPrimary") trace.oracle.exportStarted = true;
      await client.capture.screenshot({ path: path.join(outputDir, `pilot-after-${index + 1}.png`) });
      await save();
      if (trace.oracle.exportStarted) break; // Never execute a planned tail click on Share or another control.
    }
    trace.stage = "export_oracle";
    for (let i = 0; i < 20 && !exportCompleted(current); i++) {
      await new Promise((resolve) => setTimeout(resolve, 300));
      current = await snapshot("pilot-final");
    }
    trace.oracle.exportCompleted = exportCompleted(current);
    trace.success = Object.values(trace.oracle).every(Boolean);
    if (!trace.success) throw new Error(`incomplete journey oracle: ${JSON.stringify(trace.oracle)}`);
    trace.stage = "completed";
    await client.capture.screenshot({ path: path.join(outputDir, "pilot-final.png") });
    return { finishReason: "stop", text: "Real picker, watermark edit and PNG export UI observed; independent output verification required.",
      modelId: model.modelId, models: [...new Set([trace.planner, ...trace.steps.flatMap((s) => [s, s.handoff])].filter(Boolean).map((s) => s.model))],
      steps: trace.steps.filter((s) => s.acted).map((s) => ({ toolCalls: [{ toolName: s.action, input: s.actionArgs }],
        toolResults: [{ toolName: s.action, output: s.actionResult }] })), pilot: trace };
  } catch (error) {
    trace.success = false; trace.error = error.message; trace.errorCode = error.code || error.name; trace.failureStage = trace.stage;
    throw error;
  } finally { await save(); }
}

export function selfCheckExport() {
  const base = { bundleId: PICKER, enabled: true, visibleToUser: true, selected: false };
  const nodes = [
    { ...base, index: 1, ref: "e1", hittable: true, type: "GridView" },
    { ...base, index: 2, parentIndex: 1, ref: "e2", hittable: true, type: "LinearLayout" },
    { ...base, index: 3, parentIndex: 2, ref: "e3", label: ALBUM, type: "TextView" },
    { ...base, index: 4, ref: "e4", hittable: true, bundleId: "com.android.systemui" },
  ];
  assert.deepEqual(exportCandidates({ nodes }).map((n) => [n.ref, n.label]), [["e2", ALBUM]]);
  assert.throws(() => exportCandidates({ nodes, truncated: true }));
  assert.throws(() => validateExportPlan({ steps: [] }));
  assert.equal(exportCompleted({ nodes: [{ bundleId: APP, visibleToUser: true, identifier: "sharedComposeExportCounts", label: "Succeeded 0 Failed 0" }] }), false);
  assert.equal(exportCompleted({ nodes: [{ bundleId: APP, visibleToUser: true, identifier: "sharedComposeExportCounts", label: "Succeeded 1 Failed 0" }] }), true);
  const png = { bundleId: APP, visibleToUser: true, label: "PNG", selected: false };
  assert.equal(pngConfigured({ nodes: [png] }), false);
  assert.equal(pngConfigured({ nodes: [png, { ...png, identifier: "sharedComposeExportSheet" }, { ...png, identifier: "sliderTrack", enabled: false }] }), true);
}
