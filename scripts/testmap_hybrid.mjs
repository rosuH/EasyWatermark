// One bounded planner/grounder/executor for the existing Android and iOS path catalog.
import { writeFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { gateChoice, grokCall, jevCall, requireUnselected, targetSchema } from "./testmap_launch_about_pilot.mjs";
import { createIOSPicker } from "./testmap_ios_picker.mjs";

const exec = promisify(execFile);
const TARGET_ACTIONS = ["click", "fill", "remember", "longPress", "scroll", "swipe", "pan", "pinch", "set-value"];
const ACTIONS = [...TARGET_ACTIONS, "back", "keyboard", "check", "wait", "screenshot", "restore-source"];
const normalize = (v) => String(v ?? "").replace(/\s+/g, " ").trim();
const isInput = (n) => n.editable !== false && (n.editable === true || n.type === "TextView" || /TextField|TextArea|EditText/.test(n.type || ""));

export function visibleNodes(snapshot) {
  if (snapshot.truncated || snapshot.snapshotQuality?.state === "sparse" || snapshot.freshness?.staleAfterRetries) {
    throw new Error("incomplete or stale snapshot");
  }
  return (snapshot.nodes || []).filter((n) => n.visibleToUser !== false && n.interactionBlocked !== "covered");
}

export function hybridCandidates(snapshot, action) {
  const nodes = visibleNodes(snapshot);
  const byIndex = new Map((snapshot.nodes || []).map((n) => [n.index, n]));
  const parents = (n) => {
    const result = [];
    while ((n = byIndex.get(n.parentIndex)) && !result.includes(n)) result.push(n);
    return result;
  };
  const gestures = ["scroll", "swipe", "pan", "pinch", "set-value"].includes(action);
  let targets = nodes.filter((n) => n.ref && n.enabled !== false && !n.password &&
    !parents(n).some((p) => p.visibleToUser === false || p.interactionBlocked === "covered") &&
    n.bundleId !== "com.android.systemui" && !/Keyboard|InputMethod/.test(n.type || "") &&
    (action === "fill" ? isInput(n) : action === "remember" ? n.value !== undefined || n.label !== undefined : gestures
      ? n.rect?.width > 0 && n.rect?.height > 0 && (n.hittable || n.identifier || /Scroll|Recycler|Grid|SeekBar|Slider|Image|Canvas/.test(n.type || ""))
      : n.hittable || /Button|Switch|CheckBox|Cell|Link/.test(n.type || "") || ["button", "switch", "checkbox", "radio", "tab", "link"].includes(n.role)));
  if (!gestures && !["fill", "remember"].includes(action)) targets = targets.filter((n) => !targets.some((c) => c !== n && parents(c).includes(n)));
  return targets.map((n) => {
    const ancestors = parents(n);
    const descendants = nodes.filter((c) => parents(c).includes(n));
    const siblings = [...new Set(nodes.filter((c) => c.parentIndex === n.parentIndex && c.label).map((c) => c.label))];
    return { ref: n.ref, type: n.type, role: n.role, bundleId: n.bundleId, identifier: n.identifier,
      ancestorIdentifier: ancestors.find((p) => p.identifier)?.identifier,
      label: n.label || (n.inheritsLabel ? ancestors.find((p) => p.label)?.label : undefined) ||
        [...new Set(descendants.map((c) => c.label).filter(Boolean))].join(" | ") || siblings.join(" | ") || undefined,
      value: n.value, selected: n.selected, editable: isInput(n), rect: n.rect,
      ...(n.visual ? { visual: true, imageHash: n.imageHash } : {}) };
  });
}

function containsText(haystack, needle) {
  const escaped = normalize(needle).replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/ /g, "\\s*");
  return new RegExp(`${escaped}${/\d$/.test(normalize(needle)) ? "(?!\\d)" : ""}`, "i").test(normalize(haystack));
}

export function checkCondition(snapshot, condition) {
  if (condition.present === false && (snapshot.systemSurfaceOnly || snapshot.androidSnapshot?.systemSurfaceOnly)) {
    throw new Error("system occlusion cannot prove app absence");
  }
  const visible = visibleNodes(snapshot);
  // Strict absence needs the whole readable tree, including covered/hidden matches.
  const nodes = condition.present === false ? snapshot.nodes || [] : visible;
  const matches = nodes.filter((n) => (!condition.identifier || n.identifier === condition.identifier) &&
    (condition.label === undefined || n.label !== undefined && normalize(n.label) === normalize(condition.label)) &&
    (!condition.text || containsText(`${n.label || ""} ${n.value || ""}`, condition.text)));
  if (condition.present === false) return matches.length === 0;
  return matches.some((n) => ["selected", "enabled", "editable"].every((key) =>
    condition[key] === undefined || n[key] === condition[key]) &&
    (condition.value === undefined || n.value !== undefined && normalize(n.value) === normalize(condition.value)) &&
    (condition.focused === undefined || n.focused === condition.focused));
}

export function countObserved(snapshot, success, failure = 0) {
  const nodes = visibleNodes(snapshot);
  const counts = nodes.filter((n) => n.identifier === "sharedComposeExportCounts");
  if (counts.length !== 1) return false;
  const byIndex = new Map(nodes.map((n) => [n.index, n]));
  const inCounts = (node) => {
    const seen = new Set();
    while (node && !seen.has(node.index)) {
      if (node === counts[0]) return true;
      seen.add(node.index); node = byIndex.get(node.parentIndex);
    }
    return false;
  };
  const text = nodes.filter(inCounts).map((n) => `${n.label || ""} ${n.value || ""}`).join("\n");
  return new RegExp(`\\b(?:Succeeded\\s*|success\\s+)${success}(?!\\d)`, "i").test(text) &&
    new RegExp(`\\b(?:Failed\\s*|failed\\s+)${failure}(?!\\d)`, "i").test(text);
}

const conditionSchema = { type: "object", additionalProperties: false, properties: {
  identifier: { type: "string", maxLength: 300 }, text: { type: "string", maxLength: 500 },
  label: { type: "string", maxLength: 500, description: "Exact native accessibility label; text searches label/value content instead." },
  present: { type: "boolean" }, selected: { type: "boolean" }, enabled: { type: "boolean" }, editable: { type: "boolean" },
  value: { type: "string", maxLength: 2000 }, focused: { type: "boolean" },
  textVariable: { type: "string", maxLength: 32 }, valueVariable: { type: "string", maxLength: 32 },
} };
const planSchema = { type: "object", additionalProperties: false, properties: {
  overview: { type: "array", minItems: 1, maxItems: 12, items: { type: "string", maxLength: 300 } },
  complete: { type: "boolean", description: "True only if this batch finishes all route obligations; false requests another batch using the observed UI." },
  steps: { type: "array", minItems: 2, maxItems: 12, items: { type: "object", additionalProperties: false,
    properties: {
      action: { type: "string", enum: ACTIONS }, instruction: { type: "string", minLength: 1, maxLength: 500 },
      text: { type: "string", maxLength: 2000 }, optional: { type: "boolean" },
      variable: { type: "string", maxLength: 32 }, textVariable: { type: "string", maxLength: 32 },
      selection: { type: "string", enum: ["any", "unselected", "selected"] }, skipIfSelected: { type: "boolean" },
      direction: { type: "string", enum: ["up", "down", "left", "right", "top", "bottom"] },
      amount: { type: "number", minimum: 0.1, maximum: 0.8 }, repeat: { type: "integer", minimum: 1, maximum: 8 },
      dx: { type: "number", minimum: -0.8, maximum: 0.8 }, dy: { type: "number", minimum: -0.8, maximum: 0.8 },
      scale: { type: "number", minimum: 0.2, maximum: 5 }, ratio: { type: "number", minimum: 0, maximum: 1 },
      key: { type: "string", enum: ["enter", "return", "dismiss"] },
      mode: { type: "string", enum: ["in-app", "system"] },
      milliseconds: { type: "integer", minimum: 1, maximum: 3000 },
      stable: { type: "boolean" },
      // All groups must pass; each group supports selector alternatives such as the system share sheet.
      checks: { type: "array", minItems: 1, maxItems: 10, items: { type: "object", additionalProperties: false,
        properties: { anyOf: { type: "array", minItems: 1, maxItems: 8, items: conditionSchema } }, required: ["anyOf"] } },
    }, required: ["action", "instruction"] } },
}, required: ["overview", "complete", "steps"] };

export function validatePlan(answer, contract, { allowObservationOnly = false } = {}) {
  if (typeof answer.complete !== "boolean") throw new Error("invalid completion flag");
  if (!Array.isArray(answer.overview) || !answer.overview.length || answer.overview.length > 12 ||
      answer.overview.some((s) => typeof s !== "string" || !s.trim() || s.length > 300)) throw new Error("invalid route overview");
  const steps = answer?.steps;
  if (!Array.isArray(steps) || steps.length < 2 || steps.length > 80) throw new Error("invalid hybrid plan length");
  for (const step of steps) {
    if (!ACTIONS.includes(step.action) || typeof step.instruction !== "string" || !step.instruction.trim() || step.instruction.length > 500) throw new Error("invalid planned action");
    if (step.action === "check" && (!step.checks?.length || step.optional)) throw new Error("observations require mandatory concrete checks");
    if (step.action === "wait" && (!step.checks?.length && !step.milliseconds && !step.stable || step.optional)) throw new Error("wait requires concrete checks, stability or a bounded duration");
    if (["optional", "skipIfSelected", "stable"].some((k) => step[k] !== undefined && typeof step[k] !== "boolean")) throw new Error("invalid optional/selection flag");
    if (step.checks !== undefined && (!Array.isArray(step.checks) || !step.checks.length || step.checks.length > 10)) throw new Error("invalid check groups");
    for (const group of step.checks || []) {
      if (!Array.isArray(group.anyOf) || !group.anyOf.length || group.anyOf.length > 8) throw new Error("invalid alternatives");
      for (const c of group.anyOf) {
        if (!c || typeof c !== "object" || Array.isArray(c) ||
            Object.keys(c).some((key) => !Object.hasOwn(conditionSchema.properties, key)) ||
            !(c.identifier || c.label || c.text || c.textVariable || c.value !== undefined || c.valueVariable || ["selected", "enabled", "editable", "focused"].some((k) => c[k] !== undefined)) ||
            c.present === false && !c.identifier && !c.label && !c.text && !c.textVariable ||
            Object.entries(conditionSchema.properties).some(([key, schema]) => c[key] !== undefined &&
              (typeof c[key] !== schema.type || schema.maxLength !== undefined && c[key].length > schema.maxLength))) throw new Error("invalid concrete check");
      }
    }
    if (step.action === "fill" && (typeof step.text === "string") === Boolean(step.textVariable)) throw new Error("fill requires exactly one literal text or saved textVariable");
    if (step.action === "remember" && !/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(step.variable || "")) throw new Error("remember requires a variable name");
    if (step.action === "keyboard" && !["enter", "return", "dismiss"].includes(step.key)) throw new Error("invalid keyboard action");
    if (step.mode !== undefined && !["in-app", "system"].includes(step.mode)) throw new Error("invalid back mode");
    if (step.action === "restore-source" && contract.edgeId !== "export-failure-recovery") throw new Error("source recovery is a failure-path host seam only");
    for (const [key, minimum, maximum] of [["amount", 0.1, 0.8], ["repeat", 1, 8], ["dx", -0.8, 0.8], ["dy", -0.8, 0.8], ["scale", 0.2, 5], ["ratio", 0, 1], ["milliseconds", 1, 3000]]) {
      if (step[key] !== undefined && (!Number.isFinite(step[key]) || step[key] < minimum || step[key] > maximum || ["repeat", "milliseconds"].includes(key) && !Number.isInteger(step[key]))) throw new Error(`invalid ${key}`);
    }
    if (step.selection && !["any", "unselected", "selected"].includes(step.selection)) throw new Error("invalid selection guard");
    if (["scroll", "swipe"].includes(step.action) && !["up", "down", "left", "right", "top", "bottom"].includes(step.direction)) throw new Error("gesture requires direction");
    if (step.action === "pinch" && step.scale === undefined || step.action === "set-value" && step.ratio === undefined) throw new Error("gesture parameter missing");
  }
  if (steps.at(-1).action !== "check" || !allowObservationOnly && !steps.some((s) => TARGET_ACTIONS.includes(s.action))) throw new Error("plan requires an action and a final concrete check");
  return steps;
}

export async function runHybrid({ client, modules, model, outputDir, signal, goal, contract, appId, platform, deviceId,
  threshold = 0.9, recoverSource }) {
  const trace = { arm: "hybrid", controller: "generic", edgeId: contract.edgeId, platform, threshold, steps: [],
    success: false, exportObserved: false, exportDispatches: 0, sourceRecovered: false, variables: {} };
  const started = performance.now();
  const save = async () => {
    trace.controllerMs = performance.now() - started;
    const data = JSON.stringify(trace, null, 2) + "\n";
    await writeFile(path.join(outputDir, "hybrid-trace.json"), data);
    await writeFile(path.join(outputDir, "pilot-trace.json"), data); // Existing runner evidence/lifecycle boundary.
  };
  const fixture = { photoLabel: process.env.TESTMAP_PICK_EXPORT_PHOTO_LABEL, text: process.env.TESTMAP_PICK_EXPORT_TEXT };
  const referenceCounts = [...(contract.script || "").matchAll(/\bSucceeded\s+([1-9]\d*)\s*·?\s*Failed\s+0(?!\d)/gi)];
  const expectedExports = fixture.text && contract.edgeId === "pick-to-editor" ? 1
    : Number(referenceCounts.at(-1)?.[1] || { "export-one": 1, "export-two": 2 }[contract.verify] || 0);
  const planningContract = { ...contract,
    script: contract.script?.trim().startsWith("[") ? JSON.parse(contract.script) : contract.script };
  const picker = platform === "ios" && contract.edgeId === "pick-to-editor" && process.env.TESTMAP_IOS_PICKER_STATE
    ? await createIOSPicker({ client, modules, model, signal, outputDir, udid: deviceId }) : undefined;
  if (picker) trace.visualPicker = picker.trace;
  let current;
  const capture = async (name) => {
    signal.throwIfAborted();
    let snapshot = await client.capture.snapshot({ interactiveOnly: false, forceFull: true });
    if (picker) snapshot = await picker.capture(snapshot);
    visibleNodes(snapshot);
    await writeFile(path.join(outputDir, `${name}.json`), JSON.stringify(snapshot, null, 2) + "\n");
    signal.throwIfAborted();
    if (expectedExports && trace.exportDispatches > 0 && countObserved(snapshot, expectedExports)) trace.exportObserved = true;
    return snapshot;
  };
  const observe = async (step, filename) => {
    const checks = step.checks.map((g) => ({ anyOf: g.anyOf.map((c) => {
      const resolved = { ...c };
      for (const field of ["text", "value"]) if (c[`${field}Variable`]) {
        if (!Object.hasOwn(trace.variables, c[`${field}Variable`])) throw new Error("observation references an unsaved value");
        resolved[field] = trace.variables[c[`${field}Variable`]];
      }
      if (resolved.text === "" && !resolved.identifier) throw new Error("empty text observation requires a field identifier");
      return resolved;
    }) }));
    const deadline = performance.now() + 30000;
    do {
      current = await capture(filename);
      if (checks.every((g) => g.anyOf.some((c) => checkCondition(current, c)))) return;
      await new Promise((resolve) => setTimeout(resolve, 300));
    } while (performance.now() < deadline);
    throw new Error(`observation not satisfied: ${step.instruction}`);
  };
  try {
    trace.stage = "initial_observation";
    current = await capture("hybrid-initial");
    trace.stage = "planning";
    trace.planners = [];
    const planBatch = async () => {
      trace.stage = "planning";
      const record = {};
      trace.planners.push(record);
      trace.planner ??= record;
      await save();
      const answer = await grokCall({ modules, model, signal, record, name: "plan", schema: planSchema, maxOutputTokens: 8192,
      ...(picker && current.visualImageHash ? { images: picker.imagesFor(current) } : {}),
      description: "Keep an overview of the whole prepared Testmap route. Return ONLY the next 2-12 executable steps, ending with a concrete check. Set complete=false if more route obligations remain, true only for the final batch. Jev selects targets from your semantic instructions; no refs or absolute coordinates. Preserve reference order, intermediate observations, repeated exports, negative/state checks and restoration across batches. Host owns open/close/install/settings. Conditions in a group are OR; groups are AND. Optional only for incidental clickable overlays. Gestures are target-relative. Failure recovery order: observe 0 succeeded/1 failed, restore-source, select Retry, observe 1 succeeded/0 failed. Do not stop after an early export if the source requires more steps.",
      state: { goal, contract: planningContract, expectedExports, fixture, appId, platform,
        scene: visibleNodes(current).filter((n) => n.identifier || n.label || n.value).map((n) => ({
          identifier: n.identifier, label: n.label, value: n.value, type: n.type, selected: n.selected, enabled: n.enabled, editable: n.editable, focused: n.focused, visual: n.visual })),
        iosStateNotes: platform === "ios" ? "iOS native snapshots omit editable/focused and often selected. Missing means UNKNOWN. A native TextView can be filled and read back by exact identifier/value without an editable=true assertion. Do not assert editable, focused or selected, or request selection:unselected/selected, unless the actual current node explicitly supplies that flag. For PNG with unknown native selection, click the PNG option without a selection assertion; it is an idempotent format choice, and the host independently checks the exported PNG. Prefer stable identifier/value/text checks. Never infer a missing native state from a control type or screenshot." : undefined,
        overview: trace.overview, completed: trace.steps.filter((s) => s.acted || s.checked || s.remembered).map((s) => s.instruction),
        blockedStep: trace.steps.at(-1)?.handoff ? { instruction: trace.steps.at(-1).instruction,
          action: trace.steps.at(-1).action, ...trace.steps.at(-1).handoff } : undefined,
        savedValues: trace.variables,
        restorationOwner: fixture.text ? "Host exclusively verifies and restores fixture preferences AFTER the controller finishes. Do not remember or restore fixture watermark text. Editor Save only opens Output format; it does not export. Observe that configuration, select PNG, then activate sharedComposeExportPrimary to dispatch. Observe sharedComposeExportCounts with Processed 1 · Succeeded 1 · Failed 0 before stopping; preserve this native word/count order in text checks." : "Follow the reference route's explicit restoration obligations using remembered originals.",
        ...(picker ? { pickerNotes: "This owned empty simulator contains ONLY the prepared synthetic photo. Native AX omits PHPicker remote content; visual:true controls are screenshot-derived and have UNKNOWN native selection. Immediately after Choose Images, include an OPTIONAL click on Keep Add Only (or Don't Allow if offered full-library read), then a separate OPTIONAL click on Choose images anyway for Faster previews. Never select Settings or grant full library read. Then select the owned synthetic photo ONCE and Confirm selected photos. Do not assert selected=false for visual controls or confuse screenshot descriptions with native selection. End the picker batch by checking the native editor Save landmark. After the export tap, include an optional click to allow ADDING photos if the system asks, before waiting for counts. The host will verify the real Photos resource against the actual pipeline file." } : {}),
        notes: "Reference scripts may use an older locale; adapt text to current UI and prefer stable IDs. Native selected is not checkbox checked; unknown cannot prove false. A timed wait is not an assertion; use stable:true for reference wait stable and always end with check. Dismissing the keyboard does not confirm a modal edit: select its Confirm control and verify the editor returned before Save. For ORIGINAL_* restoration, use remember with a variable name to read the current field before modifying it, then fill with textVariable and check with valueVariable/textVariable; never restore an old sample literal. For prepared fixture.photoLabel select exactly that label, never recency. If fixture.text is provided, edit the watermark to exactly that text, confirm and export PNG; the host independently verifies the file and restores preferences." },
      });
      const steps = validatePlan(answer, contract, { allowObservationOnly: trace.steps.some((s) => s.acted) });
      if (steps.length > 12 || (trace.plan?.length || 0) + steps.length > 80) throw new Error("hybrid plan exceeded its bounded step budget");
      trace.overview = answer.overview || trace.overview;
      trace.planComplete = answer.complete;
      return [...steps]; // Keep each provider answer separate from the accumulating route.
    };
    trace.plan = await planBatch();
    await save();
    for (const [index, planned] of trace.plan.entries()) {
      signal.throwIfAborted();
      const step = { ...planned, acted: false };
      trace.steps.push(step);
      trace.stage = "execution";
      if (["wait", "check"].includes(step.action)) {
        if (step.stable) await client.command.wait({ stable: true, quietMs: step.milliseconds || 500, timeoutMs: 10000 });
        else if (step.milliseconds) await new Promise((resolve) => setTimeout(resolve, step.milliseconds));
        if (step.checks) { await observe(step, `hybrid-check-${index + 1}`); step.checked = true; }
        else { current = await capture(`hybrid-wait-${index + 1}`); step.waited = true; }
      } else if (step.action === "screenshot") {
        await client.capture.screenshot({ path: path.join(outputDir, `hybrid-step-${index + 1}.png`) });
      } else if (step.action === "back") {
        step.result = await client.command.back({ ...(step.mode ? { mode: step.mode } : {}), settle: true, timeoutMs: 10000 });
        step.acted = true;
        current = await capture(`hybrid-after-${index + 1}`);
      } else if (step.action === "keyboard") {
        step.result = await client.command.keyboard({ action: step.key });
        step.acted = true;
        current = await capture(`hybrid-after-${index + 1}`);
      } else if (step.action === "restore-source") {
        current = await capture(`hybrid-before-recovery-${index + 1}`);
        if (trace.sourceRecovered || trace.exportDispatches < 1 || !countObserved(current, 0, 1)) throw new Error("source restore requires this run's current failed export, once only");
        if (recoverSource) await recoverSource();
        else {
          const stateFile = process.env.TESTMAP_AGENT_DEVICE_RECOVERY_STATE;
          if (!stateFile) throw new Error("missing host-owned recovery state");
          await exec(process.env.TESTMAP_AGENT_DEVICE_PYTHON || "python3", [fileURLToPath(new URL("./testmap_hybrid_recovery.py", import.meta.url)), stateFile], { signal, timeout: 45000 });
        }
        step.acted = true; trace.sourceRecovered = true;
        current = await capture(`hybrid-after-${index + 1}`);
      } else {
        current = await capture(`hybrid-select-${index + 1}`);
        let controls = hybridCandidates(current, step.action);
        if (!controls.length || controls.length > 254) throw new Error(`invalid candidate count for ${step.action}`);
        step.candidates = controls;
        trace.stage = "provider";
        await save();
        await jevCall({ modules, signal, record: step, candidates: controls,
          state: { instruction: step.instruction, action: step.action, controls } });
        let target;
        trace.stage = "confidence_gate";
        try { target = gateChoice(step.answer, controls, threshold); step.gatePassed = true; step.selectedBy = "jev"; }
        catch (error) {
          if (error.code === "NO_TARGET" && step.optional && step.answer.confidence >= threshold) {
            step.skippedAbsent = true;
            if (step.checks) { await observe(step, `hybrid-skipped-check-${index + 1}`); step.checked = true; }
            await save(); continue;
          }
          if (error.code !== "LOW_CONFIDENCE" && !(error.code === "NO_TARGET" && step.answer.confidence < threshold)) throw error;
          trace.stage = "handoff";
          current = await capture(`hybrid-handoff-${index + 1}`);
          if (error.code === "NO_TARGET") {
            step.handoff = { reason: "LOW_CONFIDENCE_NONE", jevAnswer: step.answer };
            step.selectedBy = "grok-replan";
            trace.plan.splice(index + 1, trace.plan.length - index - 1, ...await planBatch());
            await save(); continue;
          }
          controls = hybridCandidates(current, step.action);
          step.handoff = { reason: error.code };
          const answer = await grokCall({ modules, model, signal, record: step.handoff, name: "choose", schema: targetSchema(controls),
            ...(picker && current.visualImageHash ? { images: picker.imagesFor(current) } : {}),
            description: "Jev has low confidence. Inspect the fresh UI, plan, instruction and history. Choose a current control for this step, or none to stop safely. No lifecycle or device changes.",
            state: { goal, contract, plan: trace.plan, stepIndex: index, instruction: step.instruction, action: step.action,
              completed: trace.steps.filter((s) => s.acted || s.checked).map((s) => s.instruction), jevAnswer: step.answer, controls, snapshot: current.nodes } });
          target = controls.find((n) => n.ref === answer?.ref);
          step.selectedBy = "grok-handoff";
        }
        if (!target) throw new Error("handoff selected none or unknown target");
        trace.stage = "state_guard";
        const fresh = await capture(`hybrid-before-${index + 1}`);
        let matches = hybridCandidates(fresh, step.action).filter((n) => n.bundleId === target.bundleId && n.type === target.type &&
          n.identifier === target.identifier && n.ancestorIdentifier === target.ancestorIdentifier && n.label === target.label && n.value === target.value &&
          (!target.visual || n.visual && n.imageHash === target.imageHash));
        if (matches.length > 1 && target.rect) {
          matches = matches.filter((n) => n.rect && ["x", "y", "width", "height"].every((k) =>
            Number.isFinite(target.rect[k]) && target.rect[k] === n.rect[k]));
          step.reboundBy = "semantics-and-unchanged-bounds";
        } else step.reboundBy = "unique-semantics";
        if (matches.length !== 1) throw new Error("target disappeared or became ambiguous");
        target = matches[0]; step.executedTarget = target; step.nativeSelected = target.selected ?? null;
        if (fixture.photoLabel && /^Photo taken on/.test(target.label || "") && normalize(target.label) !== normalize(fixture.photoLabel)) throw new Error("wrong fixture photo identity");
        if (step.skipIfSelected && target.selected === true) step.skippedAlreadySelected = true;
        else {
          if (step.selection === "unselected") requireUnselected(target);
          if (step.selection === "selected" && target.selected !== true) throw new Error("selected state is not explicitly true");
          const ref = fresh.refsGeneration === undefined ? target.ref : `@${target.ref.replace(/^@/, "")}~s${fresh.refsGeneration}`;
          const args = { ref, settle: true, timeoutMs: 10000 };
          trace.stage = "action";
          if (step.action === "remember") {
            if (Object.hasOwn(trace.variables, step.variable)) throw new Error("a saved original value cannot be overwritten");
            const node = visibleNodes(fresh).find((n) => n.ref === target.ref);
            const value = node?.value !== undefined ? node.value : node?.label;
            if (typeof value !== "string") throw new Error("selected original value is unavailable");
            trace.variables[step.variable] = value;
          } else if (step.action === "click" || step.action === "longPress") {
            if (target.visual) {
              if (step.action !== "click") throw new Error("visual picker supports clicks only");
              step.result = await client.interactions.click({ x: target.rect.x + target.rect.width / 2, y: target.rect.y + target.rect.height / 2, settle: true, timeoutMs: 10000 });
            } else step.result = await client.interactions[step.action](args);
            picker?.noteAction(target);
          }
          else if (step.action === "fill") {
            if (!target.editable) throw new Error("fill target is not editable");
            if (step.textVariable) {
              if (!Object.hasOwn(trace.variables, step.textVariable)) throw new Error("fill references an unsaved original value");
              step.text = trace.variables[step.textVariable];
            }
            step.result = await client.interactions.fill({ ...args, text: step.text });
          } else {
            const r = target.rect;
            if (!r || ![r.x, r.y, r.width, r.height].every(Number.isFinite) || r.width <= 0 || r.height <= 0) throw new Error("gesture target has no usable bounds");
            const x = r.x + r.width / 2, y = r.y + r.height / 2;
            if (step.action === "pinch") step.result = await client.interactions.pinch({ x, y, scale: step.scale });
            else if (step.action === "set-value") step.result = await client.interactions.click({ x: r.x + r.width * (0.05 + step.ratio * 0.9), y, settle: true });
            else if (step.action === "pan") step.result = await client.interactions.pan({ x, y, dx: r.width * (step.dx || 0), dy: r.height * (step.dy || 0), durationMs: 400 });
            else {
              const amount = step.amount || 0.6;
              const horizontal = ["left", "right"].includes(step.direction);
              const sign = (["up", "top", "left"].includes(step.direction) ? 1 : -1) * (step.action === "swipe" ? -1 : 1);
              const dx = horizontal ? r.width * amount * sign / 2 : 0;
              const dy = horizontal ? 0 : r.height * amount * sign / 2;
              step.result = await client.interactions.swipe({ from: { x: x - dx, y: y - dy }, to: { x: x + dx, y: y + dy }, count: step.repeat || (["top", "bottom"].includes(step.direction) ? 4 : 1) });
            }
          }
          step.acted = step.action !== "remember";
          if (step.action === "remember") step.remembered = true;
          if (step.action === "click" && [target.identifier, target.ancestorIdentifier].includes("sharedComposeExportPrimary") &&
              !/share|分享/i.test(target.label || "")) trace.exportDispatches++;
        }
        current = await capture(`hybrid-after-${index + 1}`);
        if (step.action === "fill") {
          // Text and its accessibility label may change; rebind before comparing either.
          matches = hybridCandidates(current, "fill").filter((n) => n.bundleId === target.bundleId && n.type === target.type &&
            n.identifier === target.identifier && n.ancestorIdentifier === target.ancestorIdentifier);
          if (!target.identifier || matches.length > 1) {
            matches = matches.filter((n) => n.rect && target.rect?.width > 0 && target.rect?.height > 0 &&
              ["x", "y", "width", "height"].every((k) => Number.isFinite(target.rect[k]) && target.rect[k] === n.rect[k]));
          }
          if (matches.length !== 1 || normalize(matches[0].value !== undefined ? matches[0].value : matches[0].label) !== normalize(step.text)) {
            throw new Error("filled value did not read back");
          }
        }
        await client.capture.screenshot({ path: path.join(outputDir, `hybrid-step-${index + 1}.png`) });
      }
      if (step.checks && !step.checked) {
        trace.stage = "observation";
        await observe(step, `hybrid-postcheck-${index + 1}`); step.checked = true;
      }
      await save();
      if (fixture.text && contract.edgeId === "pick-to-editor" && trace.exportObserved && step.checked) {
        trace.hostCompleted = "owned-fixture-export-observed";
        trace.planComplete = true;
        break; // The host verifies the PNG and restores this owned fixture's preferences.
      }
      if (index === trace.plan.length - 1 && !trace.planComplete) {
        trace.plan.push(...await planBatch());
        await save();
      }
    }
    trace.stage = "completion";
    if (!trace.steps.some((s) => s.acted)) throw new Error("no model-directed action was executed");
    if (["export-one", "export-two"].includes(contract.verify) && !trace.exportObserved) throw new Error("required export count was never observed");
    if (contract.edgeId === "export-failure-recovery" && !trace.sourceRecovered) throw new Error("failure recovery seam was not executed");
    if (picker) await picker.preserveOutput();
    trace.success = true;
    trace.stage = "completed";
    const calls = [...trace.planners, ...trace.steps.flatMap((s) => [s, s.handoff])].filter(Boolean);
    return { finishReason: "stop", text: "Hybrid plan actions and concrete observations completed; independent review remains required.",
      modelId: model.modelId, models: [...new Set(calls.map((s) => s.model).filter(Boolean))], pilot: trace,
      steps: trace.steps.filter((s) => s.acted).map((s) => ({ toolCalls: [{ toolName: s.action, input: { instruction: s.instruction } }], toolResults: [{ toolName: s.action, output: s.result || { observed: true } }] })) };
  } catch (error) {
    trace.error = error.message; trace.errorCode = error.code || error.name; trace.failureStage = trace.stage;
    throw error;
  } finally { await save(); }
}
