// Offline only: node scripts/testmap_hybrid_check.mjs
import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { visibleNodes, hybridCandidates, checkCondition, countObserved, validatePlan, runHybrid } from "./testmap_hybrid.mjs";

const failures = [];
let passed = 0;
const check = async (name, run) => {
  try { await run(); passed++; }
  catch (error) { failures.push({ name, error }); console.error(`FAIL ${name}: ${error.message}`); }
};
const action = (name, extra = {}) => ({ action: name, instruction: name, ...extra });
const observation = (condition = { identifier: "done" }) => action("check", { checks: [{ anyOf: [condition] }] });
const plan = (steps) => ({ complete: true, overview: ["offline"], steps });
const contract = { edgeId: "offline-test", verify: "ui-only" };
const node = (extra = {}) => ({ index: 1, ref: "e1", type: "Button", identifier: "target", label: "Target", ...extra });
const countsNode = (label, extra = {}) => ({ index: 2, identifier: "sharedComposeExportCounts", label, ...extra });
const snapshot = (...nodes) => ({ nodes, truncated: false });
const choice = (controls, confidence = 0.99) => ({ type: "choice", choice: controls[0].ref, confidence,
  probabilities: Object.fromEntries([...controls.map((n, i) => [n.ref, i ? 0 : 1]), ["none", 0]]) });

const originalFetch = globalThis.fetch;
const originalEnv = process.env;
// Supply an inert placeholder without reading or requiring any user credentials.
process.env = { TYPESAFE_AI_API_KEY: "offline-placeholder" };
globalThis.fetch = async () => { throw new Error("Unexpected fetch outside an offline scenario"); };
const output = await mkdtemp(path.join(tmpdir(), "ewm-hybrid-check-"));
let scenarioNumber = 0;

async function scenario({ steps = [action("click"), observation()], plans, platform = "android", native = {},
  decide = (controls) => choice(controls), scene, choose, recoverSource, caseContract = contract,
  onAction } = {}, verify) {
  const outputDir = path.join(output, String(++scenarioNumber));
  await mkdir(outputDir);
  const state = { phase: 0, captures: 0, events: [], actions: [], choices: [], waits: [], plans: [], last: null,
    tracePath: path.join(outputDir, "hybrid-trace.json") };
  const controls = () => hybridCandidates(state.last, "click");
  const invoke = (method) => async (args) => {
    state.events.push(`${method}:${state.phase}`);
    state.actions.push({ method, args, before: state.last });
    if (onAction) onAction(method, args, state);
    else state.phase++;
    return { acknowledged: true };
  };
  const client = {
    capture: {
      snapshot: async (args) => {
        assert.deepEqual(args, { interactiveOnly: false, forceFull: true });
        state.captures++;
        state.events.push(`snapshot:${state.phase}`);
        const nodes = scene ? scene(state) : [node(native), ...(state.phase ? [{ index: 2, identifier: "done", label: "Done" }] : [])];
        state.last = { nodes: nodes.map((n) => n.ref ? { ...n, ref: `e${state.captures}${n.index === 1 ? "" : `n${n.index}`}` } : n),
          refsGeneration: 100 + state.captures, truncated: false };
        return state.last;
      },
      screenshot: async () => ({}),
    },
    interactions: Object.fromEntries(["click", "fill", "longPress", "pan", "swipe", "pinch"].map((method) => [method, invoke(method)])),
    command: { back: invoke("back"), keyboard: invoke("keyboard"), wait: async (args) => {
      state.waits.push(args); state.events.push(`wait:${state.phase}`); return {};
    } },
  };
  globalThis.fetch = async (url, init) => {
    assert.equal(url, "https://api.typesafe.ai/v1/systemone");
    const body = JSON.parse(init.body);
    const candidates = Object.entries(body.questions.target.criteria).filter(([key]) => key !== "none").map(([, value]) => value);
    state.events.push(`jev:${state.phase}`);
    return { ok: true, json: async () => ({ model: "offline-jev", answers: { target: decide(candidates, state) } }) };
  };
  const modules = {
    tool: (value) => value, jsonSchema: (value) => value,
    generateText: async ({ tools, prompt, maxOutputTokens }) => {
      const name = Object.keys(tools)[0];
      assert.ok(["plan", "choose"].includes(name));
      const context = JSON.parse(prompt);
      let input = plan(steps);
      if (name === "plan") {
        state.plans.push({ context, maxOutputTokens, schema: tools[name].inputSchema });
        if (plans) {
          input = plans[state.plans.length - 1];
          assert.ok(input, "unexpected extra planning batch");
        }
      } else {
        state.events.push(`choose:${state.phase}`);
        state.choices.push(context);
        input = choose ? choose(context, state) : { ref: controls()[0].ref };
      }
      return { toolCalls: [{ toolName: name, input }] };
    },
  };
  const run = () => runHybrid({ client, modules, model: { modelId: "offline-grok" }, outputDir,
    signal: AbortSignal.timeout(1000), goal: "Offline contract check", contract: caseContract,
    appId: platform === "ios" ? "me.rosuh.easywatermark.ios" : "me.rosuh.easywatermark.debug", platform,
    recoverSource: recoverSource ? () => recoverSource(state) : undefined });
  try { await verify(run, state); }
  finally { globalThis.fetch = async () => { throw new Error("Unexpected fetch outside an offline scenario"); }; }
}

try {
  await check("Android/iOS candidates retain optional AX state and inherited semantics", () => {
    for (const platform of ["android", "ios"]) {
      const tree = snapshot(
        { index: 0, identifier: "parent-tag", label: "Parent label", type: "Group" },
        node({ parentIndex: 0, identifier: undefined, label: undefined, inheritsLabel: true, inheritsIdentifier: true,
          bundleId: platform === "android" ? "me.rosuh.easywatermark.debug" : undefined }),
        node({ index: 2, ref: "e2", enabled: false }),
        node({ index: 3, ref: "e3", visibleToUser: false }),
        node({ index: 4, ref: "e4", interactionBlocked: "covered" }),
        node({ index: 5, ref: "e5", bundleId: "com.android.systemui" }),
        node({ index: 6, ref: "e6", password: true }),
      );
      const candidates = hybridCandidates(tree, "click");
      assert.equal(candidates.length, 1);
      assert.equal(candidates[0].label, "Parent label");
      assert.equal(candidates[0].identifier ?? candidates[0].ancestorIdentifier, "parent-tag");
      assert.equal(candidates[0].selected, undefined);
      assert.equal(visibleNodes(tree).length, 5);
      const field = node({ type: "TextField", role: "textfield", value: "old" });
      assert.equal(hybridCandidates(snapshot(field), "fill")[0].editable, true);
      assert.equal(checkCondition(snapshot(field), { identifier: "target", selected: false }), false);
    }
  });

  await check("negative assertions reject occlusion/truncation/sparse/stale evidence", () => {
    for (const metadata of [{ systemSurfaceOnly: true }, { androidSnapshot: { systemSurfaceOnly: true } },
      { truncated: true }, { snapshotQuality: { state: "sparse" } }, { freshness: { staleAfterRetries: true } }]) {
      assert.throws(() => checkCondition({ nodes: [], ...metadata }, { identifier: "gone", present: false }));
    }
    assert.equal(checkCondition(snapshot(node()), { identifier: "gone", present: false }), true);
    assert.equal(checkCondition(snapshot(node()), { identifier: "target", present: false }), false);
    for (const metadata of [{ visibleToUser: false }, { interactionBlocked: "covered" }]) {
      assert.equal(checkCondition(snapshot(node(metadata)), { identifier: "target", present: false }), false);
    }
  });

  await check("export counts distinguish 1/0 from 10/0 and 1/01", () => {
    assert.equal(countObserved(snapshot(countsNode("Succeeded 1 Failed 0")), 1), true);
    for (const label of ["Succeeded 10 Failed 0", "Succeeded 1 Failed 01", "Succeeded 0 Failed 0"]) {
      assert.equal(countObserved(snapshot(countsNode(label)), 1), false, label);
    }
    assert.equal(checkCondition(snapshot(node({ label: "Succeeded 10" })), { text: "Succeeded 1" }), false);
    assert.throws(() => countObserved({ nodes: [], truncated: true }, 1));
  });

  await check("editable/watermark count text outside the export counts tag is not evidence", () => {
    const deceptive = [node({ type: "TextField", editable: true, identifier: "watermarkTextEditField", value: "Succeeded 1 Failed 0" }),
      { index: 3, identifier: "watermarkTextContent", label: "Succeeded 1 Failed 0" }];
    assert.equal(countObserved(snapshot(...deceptive), 1), false);
    assert.equal(countObserved(snapshot(...deceptive, countsNode("Succeeded 0 Failed 1")), 1), false);
    assert.equal(countObserved(snapshot(...deceptive, countsNode("Succeeded 1")), 1), false);
  });

  await check("counts require one visible tagged root and may read its subtree", () => {
    const children = [{ index: 3, parentIndex: 2, label: "Succeeded 1" },
      { index: 4, parentIndex: 2, label: "Failed 0" }];
    assert.equal(countObserved(snapshot(countsNode(""), ...children), 1), true);
    assert.equal(countObserved(snapshot(countsNode(""), ...children, countsNode("Succeeded 1 Failed 0", { index: 5 })), 1), false);
    for (const extra of [{ visibleToUser: false }, { interactionBlocked: "covered" }]) {
      assert.equal(countObserved(snapshot(countsNode("Succeeded 1 Failed 0", extra)), 1), false);
    }
  });

  await check("plan action vocabulary and mandatory concrete final check", () => {
    const options = { fill: { text: "new" }, scroll: { direction: "down" }, swipe: { direction: "left" },
      pinch: { scale: 1.5 }, "set-value": { ratio: 0.25 }, keyboard: { key: "enter" },
      wait: { checks: [{ anyOf: [{ identifier: "ready" }] }] } };
    for (const name of ["click", "fill", "longPress", "scroll", "swipe", "pan", "pinch", "set-value", "back", "keyboard", "check", "wait", "screenshot", "restore-source"]) {
      const step = name === "check" ? observation() : action(name, options[name]);
      assert.equal(validatePlan(plan([action("click"), step, observation()]), { ...contract, edgeId: "export-failure-recovery" }).length, 3);
    }
    for (const steps of [[action("click"), action("screenshot")], [action("click"), action("check")],
      [action("click"), observation({})], [action("click"), { ...observation(), optional: true }],
      [observation(), observation()], [action("install"), observation()],
      [action("set-value", { ratio: 2 }), observation()], [action("restore-source"), observation()]]) {
      assert.throws(() => validatePlan(plan(steps), contract));
    }
  });

  await check("positive native/value-only checks are concrete; negative value-only checks are not", () => {
    for (const condition of [{ editable: true, focused: true }, { selected: false, enabled: false },
      { value: "fixture" }, { value: "" }, { valueVariable: "original" }]) {
      assert.equal(validatePlan(plan([action("click"), observation(condition)]), contract).length, 2);
    }
    assert.equal(checkCondition(snapshot(node({ editable: true, focused: true })), { editable: true, focused: true }), true);
    assert.equal(checkCondition(snapshot(node({ editable: true })), { editable: true, focused: true }), false);
    assert.equal(checkCondition(snapshot(node()), { selected: false }), false, "unknown selection cannot prove false");
    assert.equal(checkCondition(snapshot(node({ value: "fixture" })), { value: "fixture" }), true);
    assert.equal(checkCondition(snapshot(node({ value: "other" })), { value: "fixture" }), false);
    assert.equal(checkCondition(snapshot(node({ value: "" })), { value: "" }), true);
    assert.equal(checkCondition(snapshot(node()), { value: "" }), false, "missing value cannot prove empty");
    for (const condition of [{}, { present: false, value: "fixture" }, { present: false, valueVariable: "original" },
      { present: false, editable: true, focused: true }]) {
      assert.throws(() => validatePlan(plan([action("click"), observation(condition)]), contract), /concrete check/);
    }
  });

  await check("native label matches exactly, never value or substrings", () => {
    const condition = { label: "Save", present: true };
    assert.equal(checkCondition(snapshot(node({ label: "Save" })), condition), true);
    assert.equal(checkCondition(snapshot(node({ label: "  Save\n" })), condition), true);
    for (const extra of [{ label: "Save to gallery" }, { label: "Autosave" }, { label: "save" },
      { label: "Other", value: "Save" }, { label: undefined, value: "Save" },
      { label: "Save", visibleToUser: false }, { label: "Save", interactionBlocked: "covered" }]) {
      assert.equal(checkCondition(snapshot(node(extra)), condition), false, JSON.stringify(extra));
    }
    assert.equal(checkCondition(snapshot(node({ label: "Save" })), { label: "Save", present: false }), false);
    assert.equal(checkCondition(snapshot(node({ label: "Save to gallery" })), { label: "Save", present: false }), true);
  });

  await check("label and native states must match the same node", () => {
    const states = { selected: false, enabled: true, editable: true, focused: true };
    const condition = { label: "Save", present: true, ...states };
    assert.equal(checkCondition(snapshot(node({ label: "Save", ...states })), condition), true);
    for (const key of Object.keys(states)) {
      for (const value of [undefined, !states[key]]) {
        assert.equal(checkCondition(snapshot(node({ label: "Save", ...states, [key]: value })), condition), false, key);
      }
    }
    assert.equal(checkCondition(snapshot(node({ label: "Save" }),
      node({ index: 2, label: "Other", ...states })), condition), false);
  });

  await check("unknown selectors and invalid label conditions fail plan validation", () => {
    for (const condition of [{ lable: "Save", present: true }, { text: "Save", lable: "Save" },
      { identifier: "target", unknown: true }, { label: "Save", unknown: true },
      { identifier: "gone", present: false, lable: "Save" }, { label: "" },
      { label: 1 }, { label: true }, { label: "x".repeat(501) }]) {
      assert.throws(() => validatePlan(plan([action("click"), observation(condition)]), contract), JSON.stringify(condition));
    }
    assert.equal(validatePlan(plan([action("click"), observation({ label: "Save", present: false })]), contract).length, 2);
  });

  await check("recorded pick/export condition shapes are accepted", () => {
    // Conditions from the failed 20261018T123519 planner batch; no local trace dependency.
    const recorded = plan([
      action("click"),
      observation({ editable: true, present: true }),
      action("check", { checks: [
        { anyOf: [{ text: "EWM BENCH auto-18dfa005057e7910", present: true }, { value: "EWM BENCH auto-18dfa005057e7910" }] },
        { anyOf: [{ label: "Save", present: true }, { identifier: "sharedComposeSaveButton", present: true }] },
      ] }),
      action("check", { stable: true, checks: [
        { anyOf: [{ text: "Succeeded 1", present: true }] },
        { anyOf: [{ text: "Failed 0", present: true }] },
      ] }),
    ]);
    assert.deepEqual(validatePlan(recorded, { edgeId: "pick-to-editor", verify: "export-one" }), recorded.steps);
  });

  await check("planner schema advertises label and executor observes it offline", () => scenario({
    steps: [action("click"), observation({ label: "Save", present: true })], native: { label: "Save" },
  }, async (run, state) => {
    const result = await run();
    const schema = state.plans[0].schema.properties.steps.items.properties.checks.items.properties.anyOf.items;
    assert.equal(schema.properties.label.type, "string");
    assert.equal(schema.additionalProperties, false);
    assert.equal(result.pilot.success, true);
    assert.equal(result.pilot.steps[1].checked, true);
    assert.equal(state.actions.length, 1);
  }));

  await check("bounded duration-only wait is not assertion or action evidence", () => scenario({
    steps: [action("click"), action("wait", { milliseconds: 1 }), observation()],
  }, async (run, state) => {
    assert.equal(validatePlan(plan([action("click"), action("wait", { milliseconds: 500 }), observation()]), contract).length, 3);
    const result = await run();
    const waited = result.pilot.steps[1];
    assert.equal(waited.waited, true);
    assert.equal(Object.hasOwn(waited, "checked"), false);
    assert.equal(waited.acted, false);
    assert.equal(result.steps.length, 1);
    assert.equal(result.pilot.steps[2].checked, true);
    assert.deepEqual(state.waits, []);
    assert.throws(() => validatePlan(plan([action("click"), action("wait", { milliseconds: 500 })]), contract));
  }));

  await check("incomplete planning batch continues with fresh UI/history/saved values", () => scenario({
    plans: [
      { overview: ["Edit then restore the original"], complete: false, steps: [
        action("remember", { variable: "original" }), action("fill", { text: "new" }),
        observation({ identifier: "target", value: "new" }),
      ] },
      { overview: ["Restore the original"], complete: true, steps: [
        action("fill", { textVariable: "original" }), observation({ identifier: "target", valueVariable: "original" }),
      ] },
    ],
    scene: (state) => [node({ type: "TextField", role: "textfield", label: "Watermark", value: state.value ?? "original" })],
    onAction: (method, args, state) => {
      assert.equal(method, "fill"); state.value = args.text; state.phase++;
    },
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.equal(state.plans.length, 2);
    assert.deepEqual(state.plans.map((p) => p.maxOutputTokens), [8192, 8192]);
    assert.deepEqual(state.plans[0].context.completed, []);
    assert.deepEqual(state.plans[0].context.savedValues, {});
    const continued = state.plans[1].context;
    assert.deepEqual(continued.completed, result.pilot.steps.slice(0, 3).map((s) => s.instruction));
    assert.deepEqual(continued.savedValues, { original: "original" });
    assert.deepEqual(continued.overview, ["Edit then restore the original"]);
    assert.equal(continued.scene[0].value, "new");
    assert.equal(state.plans[0].context.scene[0].value, "original");
    assert.deepEqual(result.pilot.planners.map((p) => p.answer.steps.length), [3, 2]);
    assert.equal(result.pilot.plan.length, 5);
    assert.equal(result.pilot.steps.length, 5);
    assert.equal(result.pilot.planComplete, true);
    assert.deepEqual(state.actions.map(({ args }) => args.text), ["new", "original"]);
    assert.equal(state.value, "original");
  }));

  await check("oversized planning batches and plans over 80 steps fail before action", () => scenario({
    plans: [{ overview: ["Too many actions"], complete: false,
      steps: [...Array.from({ length: 12 }, () => action("click")), observation()] }],
  }, async (run, state) => {
    assert.throws(() => validatePlan(plan([...Array.from({ length: 80 }, () => action("click")), observation()]), contract), /plan length/);
    await assert.rejects(run, /bounded step budget/);
    assert.equal(state.actions.length, 0);
    assert.equal(state.plans.length, 1);
    const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
    assert.equal(trace.success, false);
    assert.equal(trace.failureStage, "planning");
  }));

  await check("planning requires boolean completion and a nonempty bounded overview", () => scenario({
    plans: [{ overview: ["offline"], steps: [action("click"), observation()] }],
  }, async (run, state) => {
    for (const metadata of [{ complete: undefined }, { complete: "true" }, { overview: undefined },
      { overview: [] }, { overview: [""] }, { overview: [" "] }, { overview: [123] }, { overview: Array(13).fill("route") }]) {
      assert.throws(() => validatePlan({ ...plan([action("click"), observation()]), ...metadata }, contract), /completion flag|route overview/);
    }
    await assert.rejects(run, /completion flag/);
    assert.equal(state.actions.length, 0);
  }));

  await check("observation-only final batch requires a prior real action", async () => {
    for (const mutate of [true, false]) {
      await scenario({
        plans: [
          { complete: false, overview: ["Act then observe"], steps: [
            mutate ? action("click") : action("remember", { variable: "original" }), observation({ identifier: "target" }),
          ] },
          { complete: true, overview: ["Observe final state"], steps: [
            action("wait", { milliseconds: 1 }), observation({ identifier: "target" }),
          ] },
        ],
        native: { type: "TextField", role: "textfield", hittable: true, value: "original" },
      }, async (run, state) => {
        if (mutate) {
          const result = await run();
          assert.equal(result.pilot.success, true);
          assert.equal(result.pilot.steps.length, 4);
          assert.equal(result.pilot.steps[2].waited, true);
          assert.notEqual(result.pilot.steps[2].checked, true);
          assert.equal(result.pilot.steps[3].checked, true);
          assert.equal(result.steps.length, 1);
        } else await assert.rejects(run, /plan requires an action/);
        assert.equal(state.plans.length, 2);
        assert.equal(state.actions.length, mutate ? 1 : 0);
      });
    }
  });

  await check("wait without checks/duration and invalid duration are rejected", () => {
    for (const extra of [{}, { stable: false }, { milliseconds: 0 }, { milliseconds: 3001 }, { milliseconds: 0.5 }, { milliseconds: "500" }]) {
      assert.throws(() => validatePlan(plan([action("click"), action("wait", extra), observation()]), contract));
    }
  });

  await check("optional wait remains invalid with duration or checks", () => {
    for (const extra of [{ milliseconds: 500 }, { checks: observation().checks }, { stable: true, milliseconds: 500 }]) {
      assert.throws(() => validatePlan(plan([action("click"), action("wait", { ...extra, optional: true }), observation()]), contract));
    }
  });

  await check("stable duration wait uses client.command.wait without claiming checks", () => scenario({
    steps: [action("click"), action("wait", { stable: true, milliseconds: 500 }), observation()],
  }, async (run, state) => {
    const result = await run();
    assert.deepEqual(state.waits, [{ stable: true, quietMs: 500, timeoutMs: 10000 }]);
    assert.equal(result.pilot.steps[1].waited, true);
    assert.equal(Object.hasOwn(result.pilot.steps[1], "checked"), false);
    assert.equal(result.pilot.steps[1].acted, false);
    assert.deepEqual(state.actions.map(({ method }) => method), ["click"]);
  }));

  await check("stable wait with concrete checks defaults quiet window to 500ms", () => scenario({
    steps: [action("click"), action("wait", { stable: true, checks: observation().checks }), observation()],
  }, async (run, state) => {
    const result = await run();
    assert.deepEqual(state.waits, [{ stable: true, quietMs: 500, timeoutMs: 10000 }]);
    assert.equal(result.pilot.steps[1].checked, true);
    assert.equal(result.pilot.steps[1].acted, false);
  }));

  await check("standalone stable wait defaults to 500ms without claiming assertion proof", () => scenario({
    steps: [action("click"), action("wait", { stable: true }), observation()],
  }, async (run, state) => {
    assert.equal(validatePlan(plan([action("click"), action("wait", { stable: true }), observation()]), contract).length, 3);
    const result = await run();
    assert.deepEqual(state.waits, [{ stable: true, quietMs: 500, timeoutMs: 10000 }]);
    assert.equal(result.pilot.steps[1].waited, true);
    assert.equal(Object.hasOwn(result.pilot.steps[1], "checked"), false);
    assert.equal(result.pilot.steps[1].acted, false);
    assert.equal(result.pilot.steps[2].checked, true);
  }));

  await check("valid low confidence hands off on fresh snapshot and pins fresh execution ref", () => scenario({
    platform: "ios", decide: (controls) => choice(controls, 0.5),
    choose: (context, state) => {
      assert.equal(context.controls[0].ref, `e${state.captures}`);
      assert.notEqual(context.controls[0].ref, context.jevAnswer.choice);
      return { ref: context.controls[0].ref };
    },
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.equal(state.choices.length, 1);
    assert.equal(state.actions.length, 1);
    const { args, before } = state.actions[0];
    assert.equal(args.ref, `@${before.nodes[0].ref}~s${before.refsGeneration}`);
    assert.equal(result.pilot.steps[0].selectedBy, "grok-handoff");
  }));

  await check("visual click uses the image-bound center and retains unknown selection", () => scenario({
    platform: "ios", native: { visual: true, imageHash: "same", rect: { x: 10, y: 20, width: 30, height: 40 } },
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.equal(state.actions.length, 1);
    assert.equal(state.actions[0].method, "click");
    assert.deepEqual(state.actions[0].args, { x: 25, y: 40, settle: true, timeoutMs: 10000 });
    const step = result.pilot.steps[0];
    assert.equal(step.candidates[0].selected, undefined);
    assert.equal(step.executedTarget.selected, undefined);
    assert.equal(step.executedTarget.imageHash, "same");
    assert.equal(step.nativeSelected, null);
  }));

  await check("visual image change on fresh rebind blocks every action", () => scenario({
    platform: "ios", scene: (state) => [node({ visual: true,
      imageHash: state.captures >= 3 ? "changed" : "same", rect: { x: 10, y: 20, width: 30, height: 40 } })],
  }, async (run, state) => {
    await assert.rejects(run, /target disappeared or became ambiguous/);
    assert.equal(state.last.nodes[0].imageHash, "changed");
    assert.equal(state.actions.length, 0);
    const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
    assert.equal(trace.steps[0].candidates[0].imageHash, "same");
    assert.equal(trace.success, false);
    assert.equal(trace.failureStage, "state_guard");
  }));

  await check("duplicate labels rebind by unique unchanged bounds after refs/order change", () => scenario({
    decide: (controls) => choice([controls[1], controls[0]]),
    scene: (state) => [10, 120].map((x, i) => node({ index: i + 1, ref: `cell${i}`, type: "Cell",
      identifier: undefined, label: "Photo taken on same timestamp",
      rect: { x: state.captures >= 3 ? 130 - x : x, y: 20, width: 100, height: 80 } }))
      .concat(state.phase ? [{ index: 3, identifier: "done" }] : []),
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.equal(result.pilot.steps[0].reboundBy, "semantics-and-unchanged-bounds");
    assert.equal(state.actions.length, 1);
    const { method, args, before } = state.actions[0];
    const rebound = before.nodes.find((n) => n.rect?.x === 120);
    assert.equal(method, "click");
    assert.equal(args.ref, `@${rebound.ref}~s${before.refsGeneration}`);
    assert.equal(result.pilot.steps[0].executedTarget.rect.x, 120);
  }));

  await check("native iOS TextView fills and reads back without an editable flag or role", () => scenario({
    platform: "ios",
    steps: [action("fill", { text: "new" }), observation({ identifier: "target", value: "new" })],
    scene: (state) => [node({ type: "TextView", value: state.phase ? "new" : "original" })],
  }, async (run, state) => {
    assert.equal(hybridCandidates(snapshot(node({ type: "android.widget.TextView", editable: false })), "fill").length, 0);
    assert.equal(hybridCandidates(snapshot(node({ type: "TextView", editable: false })), "fill").length, 0);
    assert.equal((await run()).pilot.success, true);
    assert.deepEqual(state.actions.map(({ method, args }) => [method, args.text]), [["fill", "new"]]);
  }));

  await check("external input value change aborts before mutation", () => scenario({
    steps: [action("fill", { text: "new" }), observation({ identifier: "target", value: "new" })],
    scene: (state) => [node({ type: "TextField", role: "textfield", value: state.captures >= 3 ? "external" : "original" })],
  }, async (run, state) => {
    await assert.rejects(run, /target disappeared or became ambiguous/);
    assert.equal(state.last.nodes[0].value, "external");
    assert.equal(state.actions.length, 0);
    const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
    assert.equal(trace.success, false);
    assert.equal(trace.failureStage, "state_guard");
  }));

  for (const [name, corrupt] of [
    ["missing distribution", (a) => ({ ...a, probabilities: undefined })],
    ["bad total", (a) => ({ ...a, probabilities: { [a.choice]: 0.2, none: 0.2 } })],
    ["unknown choice", (a) => ({ ...a, choice: "unknown" })],
    ["extra outcome", (a) => ({ ...a, probabilities: { ...a.probabilities, extra: 0 } })],
    ["nonmaximum choice", (a) => ({ ...a, probabilities: { [a.choice]: 0.1, none: 0.9 } })],
    ["invalid confidence", (a) => ({ ...a, confidence: "0.99" })],
  ]) {
    await check(`malformed Choice: ${name} causes no action or handoff`, () => scenario({
      decide: (controls) => corrupt(choice(controls)),
    }, async (run, state) => {
      await assert.rejects(run, /invalid|unknown|distribution|confidence/i);
      assert.equal(state.actions.length, 0);
      assert.equal(state.choices.length, 0);
      assert.equal(state.plans.length, 1);
    }));
  }

  for (const [confidence, optional] of [[0.5, false], [0.5, true], [0.99, false], [0.99, true]]) {
    await check(`NO_TARGET confidence=${confidence} optional=${optional}`, () => scenario({
      steps: [action("click", { optional }), observation()],
      plans: confidence < 0.9 ? [plan([action("click", { optional }), observation()]),
        { ...plan([action("click"), observation()]), complete: "invalid" }] : undefined,
      scene: () => [node(), { index: 2, identifier: "done" }],
      decide: (controls) => ({ type: "choice", confidence, choice: "none",
        probabilities: Object.fromEntries([...controls.map((n) => [n.ref, 0]), ["none", 1]]) }),
    }, async (run, state) => {
      if (optional && confidence >= 0.9) {
        await assert.rejects(run, /no model-directed action was executed/);
        const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
        assert.equal(trace.steps[0].skippedAbsent, true);
        assert.equal(trace.success, false);
      } else await assert.rejects(run, confidence < 0.9 ? /completion flag/ : /confidence gate blocked/);
      assert.equal(state.actions.length, 0);
      assert.equal(state.choices.length, 0);
      assert.equal(state.plans.length, confidence < 0.9 ? 2 : 1);
    }));
  }

  await check("valid low-confidence none replans fresh remaining route before any click", () => scenario({
    plans: [
      { complete: true, overview: ["Confirm edit then finish"], steps: [
        action("click", { instruction: "Save from editor" }), action("click", { instruction: "Stale tail" }), observation(),
      ] },
      { complete: true, overview: ["Confirm the modal edit"], steps: [
        action("click", { instruction: "Confirm the current edit modal" }), observation(),
      ] },
    ],
    scene: (state) => [node({ label: state.captures >= 3 ? "Confirm edit" : "Save" }),
      ...(state.phase ? [{ index: 2, identifier: "done" }] : [])],
    decide: (controls, state) => state.plans.length === 1
      ? { type: "choice", choice: "none", confidence: 0.74,
        probabilities: Object.fromEntries([...controls.map((n) => [n.ref, 0]), ["none", 1]]) }
      : choice(controls),
    onAction: (method, args, state) => {
      assert.equal(state.plans.length, 2, "no click before the repair plan");
      assert.equal(method, "click");
      assert.equal(state.last.nodes[0].label, "Confirm edit");
      assert.equal(args.ref, `@${state.last.nodes[0].ref}~s${state.last.refsGeneration}`);
      state.phase++;
    },
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.equal(state.plans.length, 2);
    assert.deepEqual(state.plans[1].context.overview, ["Confirm edit then finish"]);
    assert.deepEqual(state.plans[1].context.completed, [], "unexecuted instruction is not completed history");
    assert.deepEqual(state.plans[1].context.savedValues, {});
    assert.equal(state.plans[1].context.scene[0].label, "Confirm edit");
    assert.equal(state.actions.length, 1);
    assert.equal(state.choices.length, 0, "none repairs the plan rather than choosing a stale target");
    assert.equal(result.pilot.steps[0].acted, false);
    assert.equal(result.pilot.steps[0].selectedBy, "grok-replan");
    assert.equal(result.pilot.steps[0].handoff.reason, "LOW_CONFIDENCE_NONE");
    assert.deepEqual(result.pilot.steps.map((s) => s.instruction), ["Save from editor", "Confirm the current edit modal", "check"]);
    assert.equal(result.steps.length, 1);
  }));

  await check("required unselected state blocks missing native selection", () => scenario({
    steps: [action("click", { selection: "unselected" }), observation()],
  }, async (run, state) => {
    await assert.rejects(run, /selected must be explicitly false/);
    assert.equal(state.actions.length, 0);
  }));

  await check("remember original, edit, restore saved value and check saved text/value", () => scenario({
    steps: [action("remember", { variable: "original" }), action("fill", { text: "new" }),
      action("fill", { textVariable: "original" }), action("check", { checks: [
        { anyOf: [{ identifier: "target", valueVariable: "original" }] },
        { anyOf: [{ textVariable: "original" }] },
      ] })],
    scene: (state) => [node({ type: "TextField", role: "textfield", label: "Watermark", value: state.value ?? "original" })],
    onAction: (method, args, state) => {
      assert.equal(method, "fill");
      assert.equal(args.text, state.phase ? "original" : "new");
      state.value = args.text; state.phase++;
    },
  }, async (run, state) => {
    for (const extra of [{}, { text: "literal", textVariable: "original" }]) {
      assert.throws(() => validatePlan(plan([action("fill", extra), observation()]), contract), /exactly one/);
    }
    assert.throws(() => validatePlan(plan([action("remember"), observation()]), contract), /variable name/);
    assert.equal(hybridCandidates(snapshot(node({ type: "StaticText", value: undefined })), "remember").length, 1);
    const result = await run();
    assert.equal(result.pilot.success, true);
    assert.deepEqual(result.pilot.variables, { original: "original" });
    assert.equal(result.pilot.steps[0].remembered, true);
    assert.equal(result.pilot.steps[0].acted, false);
    assert.equal(result.pilot.steps[3].checked, true);
    assert.deepEqual(state.actions.map(({ method, args }) => [method, args.text]), [["fill", "new"], ["fill", "original"]]);
    assert.equal(state.value, "original");
    assert.equal(result.steps.length, 2);
  }));

  for (const mode of ["unsaved", "overwrite", "remember-only"]) {
    await check(`saved original guard: ${mode} fails before mutation`, () => scenario({
      steps: mode === "unsaved" ? [action("fill", { textVariable: "original" }), observation({ identifier: "target" })]
        : [action("remember", { variable: "original" }),
          ...(mode === "overwrite" ? [action("remember", { variable: "original" }), action("fill", { text: "new" })] : []),
          observation({ identifier: "target", valueVariable: "original" })],
      native: { type: "TextField", role: "textfield", value: "original" },
    }, async (run, state) => {
      await assert.rejects(run, mode === "unsaved" ? /unsaved/ : mode === "overwrite" ? /overwritten/ : /no model-directed action/);
      assert.equal(state.actions.length, 0);
      const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
      assert.equal(trace.success, false);
      if (mode !== "unsaved") assert.deepEqual(trace.variables, { original: "original" });
    }));
  }

  for (const emptied of [true, false]) {
    await check(`empty fill ${emptied ? "reads explicit empty value despite nonempty label" : "rejects unrelated empty control"}`, () => scenario({
      steps: [action("fill", { text: "" }), observation({ identifier: "target", value: "" })],
      scene: (state) => [node({ type: "TextField", role: "textfield", label: "Watermark", value: state.phase && emptied ? "" : "old" }),
        node({ index: 2, ref: "e2", identifier: "unrelated", type: "Button", label: "", value: "" })],
    }, async (run, state) => {
      if (emptied) assert.equal((await run()).pilot.success, true);
      else await assert.rejects(run, /filled value did not read back/);
      assert.equal(state.actions.length, 1);
      assert.equal(state.actions[0].method, "fill");
      assert.equal(state.actions[0].args.text, "");
    }));
  }

  await check("initial success counts cannot complete export without this run's export dispatch", () => scenario({
    caseContract: { ...contract, verify: "export-one" },
    scene: () => [node(), countsNode("Succeeded 1 Failed 0"), { index: 3, identifier: "done" }],
  }, async (run, state) => {
    await assert.rejects(run, /required export count was never observed/);
    const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
    assert.equal(state.actions.length, 1);
    assert.equal(trace.exportDispatches, 0);
    assert.equal(trace.exportObserved, false);
    assert.equal(trace.success, false);
  }));

  await check("owned fixture export stops an incomplete plan before another action or planner", async () => {
    const previousText = process.env.TESTMAP_PICK_EXPORT_TEXT;
    const marker = "EWM BENCH offline-owned-fixture";
    process.env.TESTMAP_PICK_EXPORT_TEXT = marker;
    try {
      await scenario({
        caseContract: { edgeId: "pick-to-editor", verify: "export-one", setup: "home" },
        plans: [{ complete: false, overview: ["Export fixture then restore text"], steps: [
          action("click", { instruction: "Export fixture PNG" }),
          observation({ identifier: "sharedComposeExportCounts", text: "Succeeded 1 Failed 0" }),
        ] }], // Any next planner call fails the mock's unexpected-batch guard.
        scene: (state) => [node({ identifier: "sharedComposeExportPrimary", label: "Export to album" }),
          { index: 3, identifier: "watermarkTextContent", label: marker },
          ...(state.phase ? [countsNode("Succeeded 1 Failed 0")] : [])],
      }, async (run, state) => {
        const result = await run();
        assert.equal(result.pilot.success, true);
        assert.equal(result.pilot.hostCompleted, "owned-fixture-export-observed");
        assert.equal(result.pilot.exportObserved, true);
        assert.equal(result.pilot.planComplete, true);
        assert.equal(result.pilot.steps.length, 2);
        assert.equal(result.pilot.steps[1].checked, true);
        assert.equal(state.plans.length, 1);
        assert.equal(state.plans[0].context.fixture.text, marker);
        assert.match(state.plans[0].context.restorationOwner, /Host exclusively/);
        assert.deepEqual(state.actions.map(({ method }) => method), ["click"]);
        assert.deepEqual(result.pilot.variables, {});
        assert.equal(state.last.nodes.find((n) => n.identifier === "watermarkTextContent").label, marker);
      });
    } finally {
      if (previousText === undefined) delete process.env.TESTMAP_PICK_EXPORT_TEXT;
      else process.env.TESTMAP_PICK_EXPORT_TEXT = previousText;
    }
  });

  await check("pan/slider coordinates use the refreshed target bounds", () => scenario({
    steps: [action("pan", { dx: 0.25, dy: -0.5 }), action("set-value", { ratio: 0.25 }), observation()],
    scene: (state) => [node({ type: "Slider", rect: { x: state.captures * 10, y: 200, width: 200 + state.captures, height: 80 } }),
    ...(state.phase >= 2 ? [{ index: 2, identifier: "done" }] : [])],
  }, async (run, state) => {
    await run();
    assert.deepEqual(state.actions.map(({ method }) => method), ["pan", "click"]);
    for (const { method, args, before } of state.actions) {
      const r = before.nodes[0].rect;
      assert.equal(args.y, r.y + r.height / 2);
      if (method === "pan") assert.deepEqual(args, { x: r.x + r.width / 2, y: r.y + r.height / 2,
        dx: r.width * 0.25, dy: r.height * -0.5, durationMs: 400 });
      else assert.equal(args.x, r.x + r.width * (0.05 + 0.25 * 0.9));
    }
  }));

  await check("keyboard and back dispatch through client.command", () => scenario({
    steps: [action("click"), action("keyboard", { key: "enter" }), action("back"), observation()],
  }, async (run, state) => {
    await run();
    assert.deepEqual(state.actions.map(({ method }) => method), ["click", "keyboard", "back"]);
    assert.deepEqual(state.actions[1].args, { action: "enter" });
    assert.deepEqual(state.actions[2].args, { settle: true, timeoutMs: 10000 });
  }));

  await check("non-target and optional-NONE branches execute all declared postchecks", async () => {
    for (const branch of ["back", "keyboard", "screenshot", "optional-NONE"]) {
      await scenario({
        steps: [action("click"), action(branch === "optional-NONE" ? "click" : branch, {
          ...(branch === "keyboard" ? { key: "enter" } : {}),
          ...(branch === "optional-NONE" ? { optional: true } : {}),
          checks: [{ anyOf: [{ identifier: "target", valueVariable: "unsaved" }] }],
        }), observation()],
        decide: (controls, state) => branch === "optional-NONE" && state.phase > 0
          ? { type: "choice", confidence: 0.99, choice: "none",
            probabilities: Object.fromEntries([...controls.map((n) => [n.ref, 0]), ["none", 1]]) }
          : choice(controls),
      }, async (run, state) => {
        await assert.rejects(run, /observation references an unsaved value/, branch);
        const trace = JSON.parse(await readFile(state.tracePath, "utf8"));
        assert.equal(trace.success, false, branch);
        assert.equal(trace.steps.length, 2, "failure must precede the final check");
        assert.notEqual(trace.steps[1].checked, true);
        assert.deepEqual(state.actions.map(({ method }) => method),
          ["click", ...(["back", "keyboard"].includes(branch) ? [branch] : [])]);
        if (branch === "optional-NONE") assert.equal(trace.steps[1].skippedAbsent, true);
      });
    }
  });

  await check("pointer actions dispatch through client.interactions", () => scenario({
    steps: [action("click"), action("longPress"), action("swipe", { direction: "left", amount: 0.4 }),
      action("scroll", { direction: "down", amount: 0.4, repeat: 2 }), action("pinch", { scale: 1.25 }), observation()],
    native: { rect: { x: 10, y: 20, width: 200, height: 100 } },
  }, async (run, state) => {
    await run();
    assert.deepEqual(state.actions.map(({ method }) => method), ["click", "longPress", "swipe", "swipe", "pinch"]);
    assert.deepEqual(state.actions[2].args, { from: { x: 150, y: 70 }, to: { x: 70, y: 70 }, count: 1 });
    assert.deepEqual(state.actions[3].args, { from: { x: 110, y: 90 }, to: { x: 110, y: 50 }, count: 2 });
    assert.deepEqual(state.actions[4].args, { x: 110, y: 70, scale: 1.25 });
    for (const { args, before } of state.actions.slice(0, 2)) {
      assert.equal(args.ref, `@${before.nodes[0].ref}~s${before.refsGeneration}`);
    }
  }));

  const recoveryContract = { edgeId: "export-failure-recovery", verify: "export-one" };
  const recoveryScene = (state) => [node({ identifier: state.phase === 0 ? "sharedComposeExportPrimary" : "sharedComposeExportRetryFailed" }),
    countsNode(state.phase === 3 ? "Succeeded 1 Failed 0" : "Succeeded 0 Failed 1"),
    ...(state.phase === 3 ? [{ index: 3, identifier: "done" }] : [])];
  await check("failure observation precedes host restore, then Retry and success", () => scenario({
    caseContract: recoveryContract, scene: recoveryScene,
    steps: [action("click"), observation({ text: "Succeeded 0 Failed 1" }), action("restore-source"), action("click"), observation()],
    recoverSource: (state) => {
      assert.equal(state.phase, 1);
      assert.equal(countObserved(state.last, 0, 1), true);
      state.events.push(`recover:${state.phase}`); state.phase++;
    },
  }, async (run, state) => {
    const result = await run();
    assert.equal(result.pilot.sourceRecovered, true);
    assert.deepEqual(state.events.filter((e) => /^(click|recover):/.test(e)), ["click:0", "recover:1", "click:2"]);
    assert.equal(result.pilot.steps[1].checked, true);
    assert.equal(result.pilot.exportObserved, true);
  }));

  for (const duplicate of [false, true]) {
    await check(`recovery guard blocks ${duplicate ? "second restore" : "restore without failure"}`, () => scenario({
      caseContract: recoveryContract,
      scene: duplicate ? recoveryScene : () => [node({ identifier: "sharedComposeExportPrimary" }), countsNode("Succeeded 1 Failed 0")],
      steps: [action("click"), action("restore-source"), ...(duplicate ? [action("restore-source")] : []), observation()],
      recoverSource: (state) => { state.events.push("recover"); },
    }, async (run, state) => {
      await assert.rejects(run, /source restore requires/);
      assert.equal(state.events.filter((e) => e === "recover").length, duplicate ? 1 : 0);
      assert.equal(state.actions.length, 1);
    }));
  }

  for (const freshCounts of [false, true]) {
    await check(`recovery requires ${freshCounts ? "fresh failed counts" : "an actual export dispatch"}`, () => scenario({
      caseContract: recoveryContract,
      steps: [action("click"), action("restore-source"), observation()],
      scene: (state) => [node({ identifier: freshCounts ? "sharedComposeExportPrimary" : "unrelated-button" }),
        countsNode(freshCounts && state.phase === 1 && state.captures >= 5
          ? "Succeeded 1 Failed 0" : "Succeeded 0 Failed 1")],
      recoverSource: (state) => { state.events.push("recover"); },
    }, async (run, state) => {
      await assert.rejects(run, /restore|dispatch/i);
      assert.equal(state.events.includes("recover"), false);
      assert.equal(state.actions.length, 1);
    }));
  }
} finally {
  globalThis.fetch = originalFetch;
  process.env = originalEnv;
  await rm(output, { recursive: true, force: true });
}

console.log(`${passed} offline checks passed; ${failures.length} failed.`);
if (failures.length) process.exitCode = 1;
