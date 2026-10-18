// Bounded navigation pilots share candidate extraction, execution and an oracle.
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { performance } from "node:perf_hooks";

const probability = (v) => typeof v === "number" && Number.isFinite(v) && v >= 0 && v <= 1;

export function candidatesFor(snapshot) {
  if (snapshot.truncated === true || snapshot.snapshotQuality?.state === "sparse" || snapshot.systemSurfaceOnly) {
    throw new Error("incomplete or occluded snapshot");
  }
  const nodes = snapshot.nodes || [];
  const byIndex = new Map(nodes.map((n) => [n.index, n]));
  const visible = nodes.filter((n) => n.ref && n.enabled === true &&
    n.visibleToUser === true && n.interactionBlocked !== "covered" &&
    n.bundleId === "me.rosuh.easywatermark.debug");
  const ancestors = (node) => {
    const result = [];
    let parent = node.parentIndex === undefined ? undefined : byIndex.get(node.parentIndex);
    while (parent && !result.includes(parent)) {
      result.push(parent);
      parent = parent.parentIndex === undefined ? undefined : byIndex.get(parent.parentIndex);
    }
    return result;
  };
  // Compose can expose an unnamed Button beside its label inside a tagged wrapper.
  // Choose native action nodes; inherit the wrapper's semantics, never its ref.
  return visible.filter((n) => /(?:Button|Switch|CheckBox)$/.test(n.type || "") ||
    ["button", "switch", "checkbox"].includes(n.role)).map((n) => {
    const labels = n.label || n.parentIndex === undefined ? [] : [...new Set(
      visible.filter((s) => s.parentIndex === n.parentIndex && s.label).map((s) => s.label))];
    if (labels.length > 1) throw new Error("ambiguous sibling labels");
    return {
      ref: n.ref, identifier: n.identifier,
      label: n.label || labels[0],
      role: n.role, type: n.type, selected: n.selected,
      ancestorIdentifier: ancestors(n).find((p) => p.identifier)?.identifier,
    };
  });
}

export function gateChoice(answer, candidates, threshold = 0.9) {
  if (!probability(threshold)) throw new Error("invalid confidence threshold");
  if (answer?.type !== "choice" || !probability(answer.confidence)) {
    throw new Error("missing or invalid Choice confidence");
  }
  const keys = [...candidates.map((n) => n.ref), "none"];
  const p = answer.probabilities;
  if (!p || Object.keys(p).length !== keys.length ||
      keys.some((k) => !Object.hasOwn(p, k) || !probability(p[k])) ||
      Math.abs(Object.values(p).reduce((a, b) => a + b, 0) - 1) > Math.min(0.02, keys.length * 0.005 + 1e-6) ||
      !keys.includes(answer.choice) || p[answer.choice] < Math.max(...Object.values(p))) {
    throw new Error("invalid Choice distribution or unknown target");
  }
  if (answer.choice === "none" || answer.confidence < threshold) {
    throw Object.assign(new Error(`confidence gate blocked: ${answer.choice} (${answer.confidence})`), {
      code: answer.choice === "none" ? "NO_TARGET" : "LOW_CONFIDENCE",
    });
  }
  return candidates.find((n) => n.ref === answer.choice);
}

export function requireUnselected(node) {
  if (node?.selected !== false) {
    throw new Error("selected must be explicitly false; true/unknown blocks action");
  }
}

export function screenIs(snapshot, screen) {
  if (!["launch", "about"].includes(screen)) return false;
  if (snapshot.truncated === true || snapshot.snapshotQuality?.state === "sparse" || snapshot.systemSurfaceOnly) return false;
  const ids = new Set((snapshot.nodes || []).filter((n) => n.visibleToUser === true)
    .filter((n) => n.bundleId === "me.rosuh.easywatermark.debug" && n.interactionBlocked !== "covered")
    .map((n) => n.identifier));
  return screen === "launch"
    ? ids.has("launchAboutButton") && ids.has("launchPickImageButton") && !ids.has("aboutBack")
    : ids.has("aboutBack") && (ids.has("aboutCompactLayout") || ids.has("aboutOpenSourceRow"));
}

export function validateHybridPlan(answer) {
  const steps = answer?.steps;
  if (!Array.isArray(steps) || steps.length < 2 || steps.length > 4 ||
      steps.some((s) => !s || typeof s.instruction !== "string" || !s.instruction.trim() || s.instruction.length > 500 ||
        !["about", "launch"].includes(s.expectedScreen)) ||
      steps[0].expectedScreen !== "about" || steps.at(-1).expectedScreen !== "launch") {
    throw new Error("Grok plan must contain 2-4 navigation steps, visit About, and finish on Launch");
  }
  return steps;
}

export async function grokCall({ modules, model, signal, record, state, name, schema, description, maxOutputTokens, images }) {
  const start = performance.now();
  record.model = model.modelId;
  try {
    const result = await modules.generateText({ model, abortSignal: signal, maxRetries: 0, ...(maxOutputTokens ? { maxOutputTokens } : {}),
      ...(images?.length ? { messages: [{ role: "user", content: [{ type: "text", text: JSON.stringify(state) },
        ...images.map((data) => ({ type: "image", image: data, mediaType: "image/png" }))] }] } : { prompt: JSON.stringify(state) }),
      toolChoice: "required", tools: {
        [name]: modules.tool({ description, inputSchema: modules.jsonSchema(schema) }),
      },
    });
    record.usage = result.usage;
    if (result.toolCalls?.length !== 1 || result.toolCalls[0].toolName !== name) {
      throw new Error(`Grok must make exactly one ${name} decision`);
    }
    record.answer = result.toolCalls[0].input;
    return record.answer;
  } finally { record.providerMs = performance.now() - start; }
}

export function targetSchema(candidates) {
  return { type: "object", additionalProperties: false,
    properties: { ref: { type: "string", enum: [...candidates.map((n) => n.ref), "none"] } }, required: ["ref"] };
}

export async function jevCall({ state, candidates, signal, record }) {
  const start = performance.now();
  try {
    const key = process.env.TYPESAFE_AI_API_KEY;
    if (!key) throw new Error("TYPESAFE_AI_API_KEY is required");
    const response = await fetch("https://api.typesafe.ai/v1/systemone", {
      method: "POST", signal,
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "jev-latest", state, questions: {
        target: { type: "choice", instructions: "Which control performs the current instruction? Choose none if no suitable control is present.",
          criteria: { ...Object.fromEntries(candidates.map((n) => [n.ref, n])), none: "No suitable control is present" } },
      } }),
    });
    if (!response.ok) throw Object.assign(new Error(`TypeSafe HTTP ${response.status}`), { code: `HTTP_${response.status}` });
    const result = await response.json();
    record.model = result.model;
    record.usage = result.usage;
    record.answer = result.answers?.target;
    return record.answer;
  } finally { record.providerMs = performance.now() - start; }
}

export async function runLaunchAboutPilot({ client, modules, model, arm, outputDir, signal, threshold = 0.9,
  goal = "Visit About from Launch, then return to Launch.", forceHandoffStep }) {
  if (!["jev", "grok", "hybrid"].includes(arm)) throw new Error("pilot arm must be jev, grok or hybrid");
  if (forceHandoffStep !== undefined && (arm !== "hybrid" || ![1, 2].includes(forceHandoffStep))) {
    throw new Error("forced handoff is a hybrid-only test fault at step 1 or 2");
  }
  const trace = { arm, threshold, steps: [], oracle: { initialLaunch: false, aboutVisited: false, launchReturned: false },
    selectedMeaning: "native accessibility selected=false on navigation controls; not checkbox checked state" };
  const start = performance.now();
  const save = async () => {
    trace.controllerMs = performance.now() - start;
    await writeFile(path.join(outputDir, "pilot-trace.json"), JSON.stringify(trace, null, 2) + "\n");
  };
  // ponytail: navigation only, at most four planned clicks; no general action executor.
  const snapshotFor = async (screen, filename) => {
    for (let attempt = 0; attempt < 3; attempt++) {
      signal.throwIfAborted();
      const snapshot = await client.capture.snapshot({ interactiveOnly: false, forceFull: true });
      await writeFile(path.join(outputDir, filename), JSON.stringify(snapshot, null, 2) + "\n");
      if (screenIs(snapshot, screen)) return snapshot;
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
    throw new Error(`oracle did not observe ${screen}`);
  };
  try {
    trace.stage = "initial_oracle";
    let snapshot = await snapshotFor("launch", "pilot-initial.json");
    trace.oracle.initialLaunch = true;
    let plan = [
      { instruction: "Open the About screen from Launch.", expectedScreen: "about" },
      { instruction: "Return from About to Launch using the in-app Back control.", expectedScreen: "launch" },
    ];
    if (arm === "hybrid") {
      trace.stage = "planning";
      trace.planner = {};
      plan = validateHybridPlan(await grokCall({ modules, model, signal, record: trace.planner,
        state: { goal, screen: "launch", snapshot: snapshot.nodes, controls: candidatesFor(snapshot) }, name: "plan",
        description: "Plan the complete Launch to About and back journey. Use 2-4 in-app navigation clicks. Each step gives Jev a semantic instruction and its expected destination; no refs, coordinates, OS settings or external navigation.",
        schema: { type: "object", additionalProperties: false, properties: {
          steps: { type: "array", minItems: 2, maxItems: 4, items: { type: "object", additionalProperties: false,
            properties: { instruction: { type: "string", minLength: 1, maxLength: 500 },
              expectedScreen: { type: "string", enum: ["about", "launch"] } }, required: ["instruction", "expectedScreen"] } },
        }, required: ["steps"] },
      }));
      trace.plan = plan;
      trace.faultInjection = forceHandoffStep ? { type: "low-confidence", step: forceHandoffStep } : null;
      await save();
    }
    for (const [index, { instruction, expectedScreen }] of plan.entries()) {
      signal.throwIfAborted();
      const candidates = candidatesFor(snapshot);
      if (!candidates.length || candidates.length > 254) throw new Error("invalid candidate count");
      const state = { instruction, controls: candidates };
      const step = { instruction, candidates, providerMs: null, answer: null, acted: false };
      trace.steps.push(step);
      const providerStart = performance.now();
      let target;
      trace.stage = "provider";
      try {
        if (arm === "jev" || arm === "hybrid") {
          await jevCall({ state, candidates, signal, record: step });
          trace.stage = "confidence_gate";
          let gateError;
          try { target = gateChoice(step.answer, candidates, threshold); }
          catch (error) {
            if (arm !== "hybrid" || error.code !== "LOW_CONFIDENCE") throw error;
            gateError = error;
          }
          step.naturalGatePassed = !gateError;
          if (!gateError && forceHandoffStep === index + 1) {
            step.injectedGateAnswer = { ...step.answer, confidence: Math.min(step.answer.confidence, 0.89) };
            try { gateChoice(step.injectedGateAnswer, candidates, threshold); }
            catch (error) { gateError = error; }
          }
          step.gatePassed = !gateError;
          step.selectedBy = "jev";
          if (gateError) {
            trace.stage = "handoff";
            const handoffSnapshot = await client.capture.snapshot({ interactiveOnly: false, forceFull: true });
            await writeFile(path.join(outputDir, `pilot-handoff-${index + 1}.json`), JSON.stringify(handoffSnapshot, null, 2) + "\n");
            const controls = candidatesFor(handoffSnapshot);
            step.handoff = { reason: gateError.code, message: gateError.message, controls,
              testFault: Boolean(step.injectedGateAnswer) };
            const answer = await grokCall({ modules, model, signal, record: step.handoff,
              state: { goal, plan, stepIndex: index, instruction,
                completed: trace.steps.filter((s) => s.acted).map((s) => s.instruction),
                jevAnswer: step.answer, gateAnswer: step.injectedGateAnswer || step.answer,
                gateReason: gateError.message, snapshot: handoffSnapshot.nodes, controls },
              name: "choose", schema: targetSchema(controls),
              description: "Jev is uncertain. Inspect the current controls and choose one in-app navigation target for this planned step; choose none to stop. Do not blindly follow Jev.",
            });
            target = controls.find((n) => n.ref === answer?.ref);
            if (!target) throw new Error("Grok handoff selected none or an unknown target");
            step.selectedBy = "grok-handoff";
          }
        } else {
          const answer = await grokCall({ modules, model, signal, record: step, state,
            name: "choose", schema: targetSchema(candidates),
            description: "Choose the control to click for this instruction, or none.",
          });
          trace.stage = "target_selection";
          target = candidates.find((n) => n.ref === answer?.ref);
          if (!target) throw new Error("Grok selected none or an unknown target");
          step.selectedBy = "grok";
        }
      } finally { step.providerMs ??= performance.now() - providerStart; }
      // Refresh before acting: refs can change during provider inference.
      trace.stage = "unselected_check";
      const fresh = await client.capture.snapshot({ interactiveOnly: false, forceFull: true });
      await writeFile(path.join(outputDir, `pilot-before-click-${index + 1}.json`), JSON.stringify(fresh, null, 2) + "\n");
      const matches = candidatesFor(fresh).filter((n) => target.identifier
        ? n.identifier === target.identifier : n.label === target.label && n.type === target.type &&
          n.ancestorIdentifier === target.ancestorIdentifier);
      if (matches.length !== 1) throw new Error("target disappeared or became ambiguous");
      target = matches[0];
      step.nativeSelected = target.selected ?? null;
      requireUnselected(target);
      step.unselectedCheckPassed = true;
      signal.throwIfAborted();
      const ref = fresh.refsGeneration === undefined ? target.ref
        : `@${target.ref.replace(/^@/, "")}~s${fresh.refsGeneration}`;
      step.executedTarget = target;
      step.clickArgs = { ref, settle: true, timeoutMs: 10000 };
      trace.stage = "click";
      step.clickResult = await client.interactions.click(step.clickArgs);
      step.acted = true;
      trace.stage = "oracle";
      snapshot = await snapshotFor(expectedScreen, `pilot-after-${index + 1}.json`);
      if (expectedScreen === "about") trace.oracle.aboutVisited = true;
      if (expectedScreen === "launch" && trace.oracle.aboutVisited) trace.oracle.launchReturned = true;
      trace.stage = "evidence";
      await client.capture.screenshot({ path: path.join(outputDir, `pilot-after-${index + 1}.png`) });
      await save();
    }
    trace.success = Object.values(trace.oracle).every(Boolean);
    if (!trace.success) throw new Error("ordered navigation oracle incomplete");
    trace.stage = "completed";
    const calls = [trace.planner, ...trace.steps.flatMap((s) => [s, s.handoff])].filter(Boolean);
    return { finishReason: "stop", text: "Pilot oracle observed Launch → About → Launch; review required.",
      modelId: arm === "hybrid" ? model.modelId : trace.steps.at(-1).model,
      models: [...new Set(calls.map((s) => s.model).filter(Boolean))],
      usage: {
        inputTokens: calls.reduce((n, s) => n + (s.usage?.inputTokens ?? s.usage?.input_tokens ?? 0), 0),
        outputTokens: calls.reduce((n, s) => n + (s.usage?.outputTokens ?? s.usage?.output_tokens ?? 0), 0),
      },
      steps: trace.steps.map((s) => ({ toolCalls: [{ toolName: "click", input: s.clickArgs }],
        toolResults: [{ toolName: "click", output: s.clickResult }] })), pilot: trace };
  } catch (error) {
    trace.success = false;
    trace.error = error.message;
    trace.errorCode = error.code || error.name;
    trace.failureStage = trace.stage;
    throw error;
  } finally { await save(); }
}

export async function selfCheckPilot() {
  const candidates = [{ ref: "e1", selected: false }, { ref: "e2", selected: false }];
  const answer = { type: "choice", choice: "e1", confidence: 0.9, probabilities: { e1: 1, e2: 0, none: 0 } };
  assert.equal(gateChoice(answer, candidates).ref, "e1");
  for (const changed of [{ confidence: 0.89 }, { confidence: "1" }, { choice: "missing" },
    { choice: "none", probabilities: { e1: 0, e2: 0, none: 1 } },
    { probabilities: { e1: 0.2, e2: 0.8, none: 0 } }, { probabilities: { e1: 1 } }]) {
    assert.throws(() => gateChoice({ ...answer, ...changed }, candidates));
  }
  requireUnselected({ selected: false });
  for (const n of [{ selected: true }, {}, { selected: "false" }]) assert.throws(() => requireUnselected(n));
  assert.throws(() => candidatesFor({ truncated: true, nodes: [] }));
  assert.throws(() => candidatesFor({ nodes: [], snapshotQuality: { state: "sparse" } }));
  assert.throws(() => gateChoice({ ...answer, confidence: undefined }, candidates));
  const many = Array.from({ length: 254 }, (_, i) => ({ ref: `e${i}` }));
  assert.throws(() => gateChoice({ type: "choice", confidence: 1, choice: "e0",
    probabilities: Object.fromEntries([...many.map((n) => [n.ref, 0]), ["none", 0]]) }, many));
  const tree = { nodes: [
    { index: 0, ref: "e1", identifier: "about", enabled: true, visibleToUser: true,
      bundleId: "me.rosuh.easywatermark.debug" },
    { index: 1, parentIndex: 0, ref: "e2", type: "android.widget.Button", enabled: true, visibleToUser: true,
      bundleId: "me.rosuh.easywatermark.debug" },
  ] };
  assert.deepEqual(candidatesFor(tree).map((n) => [n.ref, n.ancestorIdentifier]), [["e2", "about"]]);
  const outputDir = await mkdtemp(path.join(tmpdir(), "jev-pilot-check-"));
  const oldFetch = globalThis.fetch;
  const oldKey = process.env.TYPESAFE_AI_API_KEY;
  let clicks = 0;
  const node = (identifier, ref) => ({ identifier, ref, type: "android.widget.Button", selected: false, enabled: true,
    visibleToUser: true, bundleId: "me.rosuh.easywatermark.debug" });
  const launch = { nodes: [node("launchAboutButton", "e1"), node("launchPickImageButton", "e2")] };
  const about = { nodes: [node("aboutBack", "e3"), node("aboutCompactLayout", "e4")] };
  const client = {
    capture: { snapshot: async () => clicks === 1 ? about : launch, screenshot: async () => {} },
    interactions: { click: async () => { clicks++; } },
  };
  process.env.TYPESAFE_AI_API_KEY = "offline-test-only";
  try {
    globalThis.fetch = async () => ({ ok: true, json: async () => ({
      model: "mock", answers: { target: { ...answer, confidence: 0.89 } },
    }) });
    await assert.rejects(runLaunchAboutPilot({ client, arm: "jev", outputDir,
      signal: AbortSignal.timeout(1000) }), /confidence gate blocked/);
    assert.equal(clicks, 0, "low confidence must never reach click");
    globalThis.fetch = async () => ({ ok: true, json: async () => ({
      model: "mock", answers: { target: clicks === 0 ? answer : { ...answer, choice: "e3",
        probabilities: { e3: 1, e4: 0, none: 0 } } },
    }) });
    const result = await runLaunchAboutPilot({ client, arm: "jev", outputDir,
      signal: AbortSignal.timeout(1000) });
    assert.equal(clicks, 2);
    assert.equal(result.pilot.success, true);
    assert.equal(JSON.parse(await readFile(path.join(outputDir, "pilot-trace.json"))).oracle.launchReturned, true);
    clicks = 0;
    launch.nodes[0].selected = true;
    await assert.rejects(runLaunchAboutPilot({ client, arm: "jev", outputDir,
      signal: AbortSignal.timeout(1000) }), /explicitly false/);
    assert.equal(clicks, 0, "selected control must never reach click");
    delete launch.nodes[0].selected;
    await assert.rejects(runLaunchAboutPilot({ client, arm: "jev", outputDir,
      signal: AbortSignal.timeout(1000) }), /explicitly false/);
    assert.equal(clicks, 0, "missing selected state must never reach click");
    launch.nodes[0].selected = false;
    globalThis.fetch = async () => ({ ok: false, status: 529 });
    await assert.rejects(runLaunchAboutPilot({ client, arm: "jev", outputDir,
      signal: AbortSignal.timeout(1000) }), /HTTP 529/);
    const failed = JSON.parse(await readFile(path.join(outputDir, "pilot-trace.json")));
    assert.equal(failed.failureStage, "provider");
    assert.equal(failed.errorCode, "HTTP_529");
    assert.ok(failed.steps[0].providerMs > 0);
    assert.equal(clicks, 0, "provider failure must never reach click");
    const plan = { steps: [
      { instruction: "Open About", expectedScreen: "about" },
      { instruction: "Return to Launch", expectedScreen: "launch" },
    ] };
    validateHybridPlan(plan);
    for (const steps of [[], [plan.steps[0]], [...plan.steps].reverse(),
      [null, plan.steps[1]], [{ instruction: "bad", expectedScreen: "elsewhere" }, plan.steps[1]]]) {
      assert.throws(() => validateHybridPlan({ steps }));
    }
    let handoffs = [];
    let plannerCalls = 0;
    const modules = { tool: (x) => x, jsonSchema: (x) => x,
      generateText: async ({ tools, prompt }) => {
        const isPlan = Boolean(tools.plan);
        if (isPlan) plannerCalls++;
        else handoffs.push(JSON.parse(prompt));
        return { toolCalls: [{ toolName: isPlan ? "plan" : "choose", input: isPlan ? plan : { ref: clicks === 0 ? "e1" : "e3" } }],
          usage: { inputTokens: 1, outputTokens: 1 } };
      },
    };
    const hybrid = (forceHandoffStep) => runLaunchAboutPilot({ client, modules,
      model: { modelId: "mock-grok" }, arm: "hybrid", outputDir, forceHandoffStep,
      signal: AbortSignal.timeout(3000) });
    const respond = (confidence) => ({ ok: true, json: async () => ({ model: "mock-jev", answers: {
      target: clicks === 0 ? { ...answer, confidence } : { ...answer, choice: "e3", confidence,
        probabilities: { e3: 1, e4: 0, none: 0 } },
    } }) });
    for (const forced of [undefined, 1, 2]) {
      clicks = 0; handoffs = []; plannerCalls = 0;
      globalThis.fetch = async () => respond(0.95);
      const result = await hybrid(forced);
      assert.equal(plannerCalls, 1);
      assert.equal(clicks, 2);
      assert.equal(result.pilot.success, true);
      assert.equal(handoffs.length, forced ? 1 : 0);
      if (forced) {
        const step = result.pilot.steps[forced - 1];
        assert.equal(step.selectedBy, "grok-handoff");
        assert.equal(step.answer.confidence, 0.95, "raw Jev answer must not be changed");
        assert.equal(step.injectedGateAnswer.confidence, 0.89);
        assert.equal(step.handoff.testFault, true);
        assert.equal(handoffs[0].plan.length, 2);
        assert.equal(handoffs[0].completed.length, forced - 1);
        assert.ok(handoffs[0].snapshot.length > 0);
      }
    }
    clicks = 0; handoffs = [];
    globalThis.fetch = async () => respond(clicks === 0 ? 0.89 : 0.95);
    const natural = await hybrid();
    assert.equal(natural.pilot.steps[0].handoff.testFault, false);
    assert.equal(handoffs.length, 1, "natural low confidence hands back to Grok");
    clicks = 0; handoffs = [];
    globalThis.fetch = async () => ({ ok: true, json: async () => ({
      model: "mock", answers: { target: { ...answer, confidence: 0.89, probabilities: {} } },
    }) });
    await assert.rejects(hybrid(), /invalid Choice/);
    assert.equal(handoffs.length, 0, "malformed responses must not trigger handoff");
    assert.equal(clicks, 0);
    globalThis.fetch = async () => respond(0.95);
    launch.nodes[0].selected = true;
    await assert.rejects(hybrid(1), /explicitly false/);
    assert.equal(clicks, 0, "Grok handoff cannot bypass native selected-state guard");
    launch.nodes[0].selected = false;
    await assert.rejects(runLaunchAboutPilot({ client, arm: "jev", outputDir,
      forceHandoffStep: 1, signal: AbortSignal.timeout(1000) }), /hybrid-only/);
  } finally {
    globalThis.fetch = oldFetch;
    if (oldKey === undefined) delete process.env.TYPESAFE_AI_API_KEY;
    else process.env.TYPESAFE_AI_API_KEY = oldKey;
    await rm(outputDir, { recursive: true, force: true });
  }
}
