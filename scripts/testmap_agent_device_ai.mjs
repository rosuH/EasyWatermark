#!/usr/bin/env node

import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { runLaunchAboutPilot, selfCheckPilot } from "./testmap_launch_about_pilot.mjs";

import { runPickExportPilot, selfCheckExport } from "./testmap_pick_export_pilot.mjs";
import { runHybrid } from "./testmap_hybrid.mjs";

const PINNED_AGENT_DEVICE = "0.21.2";
const DEFAULT_MODEL = "cursor/grok-4.7@500k";
const DEFAULT_BASE_URL = "http://127.0.0.1:3425/v1";
const MAX_AGENT_STEPS = 40;

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === "--self-check") {
      parsed.selfCheck = true;
      continue;
    }
    if (!token.startsWith("--")) {
      throw new Error(`unexpected argument: ${token}`);
    }
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`missing value for ${token}`);
    }
    parsed[token.slice(2)] = value;
    index += 1;
  }
  return parsed;
}

function required(args, name) {
  const value = String(args[name] || "").trim();
  if (!value) {
    throw new Error(`missing --${name}`);
  }
  return value;
}

function timeoutMs(args) {
  const raw = Number(args["timeout-ms"] || process.env.TESTMAP_AGENT_DEVICE_TIMEOUT_MS || 360000);
  if (!Number.isFinite(raw) || raw <= 0) {
    throw new Error("--timeout-ms must be a positive integer");
  }
  return Math.trunc(raw);
}

export function controllerFor(args, edgeId, platform) {
  if (args["pilot-arm"]) {
    const arm = args["pilot-arm"];
    if (!["jev", "grok", "hybrid"].includes(arm) || platform !== "android" ||
        !["launch-to-about", "pick-to-editor"].includes(edgeId) || (edgeId === "pick-to-editor" && arm === "jev")) {
      throw new Error("pilot requires Android launch-to-about (jev/grok/hybrid) or pick-to-editor (grok/hybrid)");
    }
    return { controller: arm, pilotArm: arm };
  }
  const controller = String(args.controller || process.env.TESTMAP_AGENT_DEVICE_AI_CONTROLLER || "hybrid").trim().toLowerCase();
  if (!["hybrid", "grok"].includes(controller)) throw new Error("AI controller must be hybrid or grok");
  return { controller, pilotArm: undefined };
}

async function caseContract(args, edgeId, platform) {
  let contract;
  if (args["case-contract"]) contract = JSON.parse(args["case-contract"]);
  else {
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
    const catalog = JSON.parse(await readFile(path.join(root, "docs/testing/agent-device-cases.json"), "utf8"));
    const entry = catalog.cases.find((c) => c.id === edgeId);
    if (!entry?.supported?.[platform]) throw new Error(`unsupported Agent Device binding: ${edgeId}@${platform}`);
    contract = { edgeId, platform, supported: true, title: entry.title, setup: entry[`${platform}_setup`] || entry.setup,
      verify: entry.verify, honesty: entry.honesty,
      script: await readFile(path.join(root, entry.script[platform]), "utf8") };
    if (edgeId === "export-failure-recovery") contract.recovery = {
      script: await readFile(path.join(root, `docs/testing/agent-device/scripts/export-failure-retry@${platform}.ad`), "utf8") };
  }
  if (contract?.edgeId !== edgeId || contract.platform !== platform || contract.supported !== true ||
      typeof contract.script !== "string" || !contract.script.trim() || contract.script.length > 50000 ||
      !["ui-only", "export-one", "export-two"].includes(contract.verify)) throw new Error("invalid or unsupported case contract");
  return contract;
}

async function loadJevKey() {
  if (process.env.TYPESAFE_AI_API_KEY) return;
  const file = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../build/agent-device/jev-pilot.env");
  try {
    const line = (await readFile(file, "utf8")).split(/\r?\n/).find((s) => s.startsWith("TYPESAFE_AI_API_KEY="));
    if (line) process.env.TYPESAFE_AI_API_KEY = line.slice("TYPESAFE_AI_API_KEY=".length).trim().replace(/^(["'])(.*)\1$/, "$2");
  } catch (error) { if (error.code !== "ENOENT") throw error; }
}

async function loadModules() {
  const [ai, compatible, agentSdk] = await Promise.all([
    import("ai"),
    import("@ai-sdk/openai-compatible"),
    import("agent-device/ai-sdk"),
  ]);
  const packageVersion = async (name) => {
    let current = path.dirname(fileURLToPath(import.meta.resolve(name)));
    while (current !== path.dirname(current)) {
      try {
        const manifest = JSON.parse(
          await readFile(path.join(current, "package.json"), "utf8"),
        );
        if (manifest.name === name) {
          return manifest.version;
        }
      } catch {
        // Keep walking toward the package root.
      }
      current = path.dirname(current);
    }
    throw new Error(`cannot resolve package version for ${name}`);
  };
  const versions = {
    ai: await packageVersion("ai"),
    openaiCompatible: await packageVersion("@ai-sdk/openai-compatible"),
    agentDevice: await packageVersion("agent-device"),
  };
  if (versions.agentDevice !== PINNED_AGENT_DEVICE) {
    throw new Error(
      `agent-device dependency must be ${PINNED_AGENT_DEVICE}, got ${versions.agentDevice}`,
    );
  }
  return { ...ai, ...compatible, ...agentSdk, versions };
}

function safeJson(value) {
  return JSON.stringify(
    value,
    (_key, item) => (typeof item === "bigint" ? String(item) : item),
    2,
  );
}

function compact(value, limit = 4000) {
  const text = typeof value === "string" ? value : safeJson(value);
  return text.length <= limit ? text : `${text.slice(0, limit)}\n...[truncated]`;
}

async function writeJsonAtomic(target, value) {
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = `${target}.tmp-${process.pid}`;
  await writeFile(temporary, `${safeJson(value)}\n`, "utf8");
  await rename(temporary, target);
}

function traceFor(result, model) {
  return {
    model,
    finishReason: result?.finishReason,
    text: result?.text || "",
    usage: result?.usage,
    steps: (result?.steps || []).map((step, index) => ({
      index: index + 1,
      finishReason: step.finishReason,
      text: step.text || "",
      toolCalls: (step.toolCalls || []).map((call) => ({
        toolName: call.toolName,
        toolCallId: call.toolCallId,
        input: compact(call.input),
      })),
      toolResults: (step.toolResults || []).map((item) => ({
        toolName: item.toolName,
        toolCallId: item.toolCallId,
        output: compact(item.output),
      })),
    })),
  };
}

async function captureEvidence(client, outputDir) {
  const captured = {};
  try {
    await client.capture.screenshot({ path: path.join(outputDir, "post.png") });
    captured.postScreenshot = true;
  } catch (error) {
    captured.postScreenshotError = error instanceof Error ? error.message : String(error);
  }
  try {
    const snapshot = await client.capture.snapshot({ interactiveOnly: true });
    await writeJsonAtomic(path.join(outputDir, "post-snapshot.json"), snapshot);
    captured.postSnapshot = true;
  } catch (error) {
    captured.postSnapshotError = error instanceof Error ? error.message : String(error);
  }
  return captured;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.selfCheck) {
    const { default: assert } = await import("node:assert/strict");
    const previousController = process.env.TESTMAP_AGENT_DEVICE_AI_CONTROLLER;
    try {
      delete process.env.TESTMAP_AGENT_DEVICE_AI_CONTROLLER;
      assert.equal(controllerFor({}, "launch-to-about", "android").controller, "hybrid");
    } finally {
      if (previousController === undefined) delete process.env.TESTMAP_AGENT_DEVICE_AI_CONTROLLER;
      else process.env.TESTMAP_AGENT_DEVICE_AI_CONTROLLER = previousController;
    }
    for (const edge of ["launch-to-about", "pick-to-editor"]) {
      assert.deepEqual(controllerFor({ controller: "hybrid" }, edge, "android"), { controller: "hybrid", pilotArm: undefined });
    }
    assert.equal(controllerFor({ controller: "grok" }, "editor-to-about", "ios").pilotArm, undefined);
    assert.equal(controllerFor({ "pilot-arm": "grok", controller: "hybrid" }, "launch-to-about", "android").pilotArm, "grok");
    assert.equal(controllerFor({ controller: "hybrid" }, "editor-to-about", "android").controller, "hybrid");
    assert.equal(controllerFor({ controller: "hybrid" }, "launch-to-about", "ios").controller, "hybrid");
    const catalog = JSON.parse(await readFile(new URL("../docs/testing/agent-device-cases.json", import.meta.url), "utf8"));
    for (const entry of catalog.cases) for (const os of ["android", "ios"]) {
      if (entry.supported?.[os]) assert.equal((await caseContract({}, entry.id, os)).edgeId, entry.id);
      else await assert.rejects(caseContract({}, entry.id, os));
    }
    assert.throws(() => controllerFor({ controller: "unknown" }, "launch-to-about", "android"));
    await selfCheckPilot();
    selfCheckExport();
    const modules = await loadModules();
    for (const name of ["ToolLoopAgent", "createAgentDeviceTools", "isStepCount"]) {
      if (typeof modules[name] !== "function") {
        throw new Error(`AI SDK module is missing ${name}`);
      }
    }
    console.log(safeJson(modules.versions));
    return 0;
  }

  const platform = required(args, "platform");
  if (platform !== "android" && platform !== "ios") {
    throw new Error(`unsupported platform: ${platform}`);
  }
  const serial = String(args.serial || "").trim();
  const udid = String(args.udid || "").trim();
  if ((platform === "android") !== Boolean(serial) || (platform === "ios") !== Boolean(udid)) {
    throw new Error("pin exactly one matching --serial or --udid");
  }

  const outputDir = path.resolve(required(args, "output-dir"));
  const edgeId = required(args, "edge-id");
  const session = required(args, "session");
  const appId = required(args, "app-id");
  const goal = required(args, "goal");
  const modelId = String(args.model || process.env.TESTMAP_AGENT_DEVICE_AI_MODEL || DEFAULT_MODEL);
  const baseURL = String(args["base-url"] || process.env.MAGPIE_BASE_URL || DEFAULT_BASE_URL);
  const limitMs = timeoutMs(args);
  const { controller, pilotArm } = controllerFor(args, edgeId, platform);
  const contract = !pilotArm && controller === "hybrid" ? await caseContract(args, edgeId, platform) : undefined;
  const faultStep = process.env.TESTMAP_AGENT_DEVICE_PILOT_FORCE_HANDOFF_STEP;
  if (faultStep && (edgeId !== "launch-to-about" || pilotArm !== "hybrid" || !["1", "2"].includes(faultStep))) {
    throw new Error("forced handoff requires hybrid and step 1 or 2");
  }
  if (controller === "hybrid" || pilotArm === "jev") {
    await loadJevKey();
    if (!process.env.TYPESAFE_AI_API_KEY) throw new Error("TYPESAFE_AI_API_KEY is required (environment or build/agent-device/jev-pilot.env)");
  }
  if (pilotArm && edgeId === "pick-to-editor" &&
      (!process.env.TESTMAP_PICK_EXPORT_TEXT || !process.env.TESTMAP_PICK_EXPORT_PHOTO_LABEL)) {
    throw new Error("pick-to-editor hybrid requires prepared fixture evidence; use scripts/testmap_run.py or the comparison runner");
  }
  const deviceKey = platform === "android" ? "serial" : "udid";
  const deviceId = platform === "android" ? serial : udid;

  await mkdir(outputDir, { recursive: true });
  let client;
  let exitCode = 1;
  let resultDocument = {
    status: "failed",
    timed_out: false,
    agent_status: "failed",
    runner: "ai-sdk",
    controller,
    provider: controller === "hybrid" ? "magpie+typesafe" : "magpie",
    model: pilotArm === "jev" ? "jev-latest" : modelId,
    ...(pilotArm ? { pilot_arm: pilotArm, provider: pilotArm === "hybrid" ? "magpie+typesafe" : pilotArm === "jev" ? "typesafe" : "magpie" } : {}),
    edge_id: edgeId,
    platform,
    session,
  };

  try {
    const modules = await loadModules();
    const provider = modules.createOpenAICompatible({
      name: "magpie",
      baseURL: baseURL.replace(/\/+$/, ""),
      apiKey: process.env.MAGPIE_API_KEY || "local",
    });
    const model = provider.chatModel(modelId);
    const agentTools = await modules.createAgentDeviceTools({ session, platform });
    client = agentTools.client;

    // The host owns lifecycle and device binding. The model only gets UI actions.
    const tools = { ...agentTools.tools };
    delete tools.open;
    delete tools.close;

    const opened = await client.apps.open({
      app: appId,
      platform,
      [deviceKey]: deviceId,
      foreground: true,
      ...(pilotArm || contract?.setup === "home" && /(?:open[^\n]*--relaunch|"relaunch"\s*:\s*true)/.test(contract.script)
        ? { relaunch: true } : {}),
      timeoutMs: limitMs,
    });
    const observedDevice = opened?.device?.identifiers?.[deviceKey] || opened?.identifiers?.[deviceKey];
    if (observedDevice && observedDevice !== deviceId) {
      throw new Error(
        `device pin mismatch: expected ${deviceKey}=${deviceId}, got ${observedDevice}`,
      );
    }
    await writeJsonAtomic(path.join(outputDir, "open.json"), opened);
    try {
      await client.capture.screenshot({ path: path.join(outputDir, "pre.png") });
    } catch {
      // Screenshot evidence is useful but not a prerequisite for the action loop.
    }

    const agent = new modules.ToolLoopAgent({
      model,
      instructions: [
        "You execute one already-prepared EasyWatermark end-to-end verification path.",
        "Use Agent Device tools only. Inspect the accessibility snapshot before acting.",
        "Prefer semantic selectors. Use coordinates only when the snapshot has no usable target.",
        "For a system 'isn't responding' dialog, choose Wait with the normal click or press tool; never use alert.dismiss because Android may map it to Close app.",
        "Do not install, clear data, change OS settings, or close the app.",
        "The host already opened and pinned the app, platform, and device.",
        "Stop as soon as the requested end state is visibly confirmed.",
        "Never claim a product PASS. Return factual observations and uncertainty.",
      ].join(" "),
      tools,
      stopWhen: modules.isStepCount(MAX_AGENT_STEPS),
    });
    const generated = pilotArm ? await (edgeId === "pick-to-editor" ? runPickExportPilot : runLaunchAboutPilot)({
      client, modules, model, arm: pilotArm, outputDir, goal,
      forceHandoffStep: faultStep ? Number(faultStep) : undefined,
      signal: AbortSignal.timeout(limitMs),
    }) : controller === "hybrid" ? await runHybrid({
      client, modules, model, outputDir, goal, contract, appId, platform, deviceId,
      signal: AbortSignal.timeout(limitMs),
    }) : await agent.generate({
      prompt: goal,
      abortSignal: AbortSignal.timeout(limitMs),
    });
    const toolCallCount = (generated.steps || []).reduce(
      (sum, step) => sum + (step.toolCalls || []).length,
      0,
    );
    const completed = generated.finishReason === "stop" && toolCallCount > 0;
    resultDocument = {
      ...resultDocument,
      status: completed ? "executed_review_required" : "failed",
      timed_out: false,
      agent_status: completed ? "completed" : "failed",
      finish_reason: generated.finishReason || "",
      text: generated.text || "",
      tool_calls: toolCallCount,
      usage: generated.usage,
      model: generated.modelId || modelId,
      models: generated.models,
      ...(generated.pilot ? { pilot: generated.pilot } : {}),
      ...(pilotArm ? { pilot_arm: pilotArm } : {}),
      error: completed
        ? ""
        : "model finished without a completed tool loop and device observations",
    };
    await writeJsonAtomic(path.join(outputDir, "agent-trace.json"), traceFor(generated, generated.modelId || modelId));
    exitCode = completed ? 0 : 1;
  } catch (error) {
    const rawName = error && typeof error === "object" ? String(error.name || "") : "";
    const timedOut = rawName === "TimeoutError" || rawName === "AbortError";
    const message = error instanceof Error ? error.message : String(error);
    resultDocument = {
      ...resultDocument,
      status: timedOut ? "timed_out" : "failed",
      timed_out: timedOut,
      agent_status: timedOut ? "timed_out" : "failed",
      error: message,
    };
    await writeFile(path.join(outputDir, "agent-error.log"), `${message}\n`, "utf8");
    console.error(`[agent-device-ai] ${message}`);
    exitCode = timedOut ? 124 : 1;
  } finally {
    if (client) {
      const evidence = await captureEvidence(client, outputDir);
      try {
        await client.sessions.close();
      } catch (error) {
        evidence.closeError = error instanceof Error ? error.message : String(error);
      }
      resultDocument.evidence = evidence;
      if (evidence.closeError && resultDocument.status === "executed_review_required") {
        resultDocument.status = "failed";
        resultDocument.agent_status = "failed";
        resultDocument.error = `session close failed: ${evidence.closeError}`;
        exitCode = 1;
      }
    }
    await writeJsonAtomic(path.join(outputDir, "result.json"), resultDocument);
  }
  return exitCode;
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch(async (error) => {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[agent-device-ai] ${message}`);
    process.exitCode = 1;
  });
