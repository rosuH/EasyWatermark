import { spawnSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { namedScreenshotPath, normalizeAgentModelEvidence, normalizeRunStatus, screenshotsByStep } from './report-normalizer.mjs';

const cwd = process.cwd();
const runId = process.env.EWM_E2E_RUN_ID?.trim() ?? '';
const runIdPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const output = process.env.EWM_E2E_OUTPUT?.trim() ?? '';
const manifestPath = process.env.EWM_E2E_SETUP_STATE?.trim() ?? '';
const device = process.env.EWM_E2E_DEVICE?.trim() ?? '';
const modelId = process.env.EWM_E2E_MODEL?.trim() || 'cursor/auto';
const baseURL = process.env.EWM_E2E_BASE_URL?.trim() || 'http://127.0.0.1:3425/v1';
const outputRelative = `.runs/${runId}`;
const outputRoot = path.resolve(cwd, outputRelative);
let manifest;
let report;
let childExitCode;
let childSignal;
let stepRows = [];
let tracePath;
let actualModel;
let modelProof;
const reasons = [];

function safeRelative(file) {
  const normalized = file.replaceAll('\\', '/').replace(/^\.\//, '');
  if (!normalized || path.posix.isAbsolute(normalized) || normalized.split('/').some((part) => part === '..' || part === '')) {
    return undefined;
  }
  return normalized;
}

function writeResult(status, reason) {
  mkdirSync(outputRoot, { recursive: true });
  const result = {
    status,
    ...(reason ? { reason } : {}),
    model: actualModel ?? modelId,
    provider: 'magpie',
    ...(tracePath ? { trace_path: tracePath } : {}),
    ...(existsSync(path.join(outputRoot, 'report.json')) ? { report_path: 'report.json' } : {}),
    steps: stepRows,
    ...(manifest
      ? {
          fixture: {
            name: manifest.fixture_name,
            uri: manifest.fixture_uri,
            sha256: manifest.fixture_sha256,
            marker: manifest.marker,
          },
          testmap_run_id: manifest.testmap_run_id,
        }
      : {}),
    ...(modelProof ? { model_proof: modelProof } : {}),
    ...(childExitCode !== undefined ? { process_exit_code: childExitCode } : {}),
    ...(childSignal ? { process_signal: childSignal } : {}),
  };
  writeFileSync(path.join(outputRoot, 'testmap-result.json'), `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' });
}

function outputIsAvailable() {
  if (!existsSync(outputRoot)) return true;
  try {
    const stat = lstatSync(outputRoot);
    return stat.isDirectory() && !stat.isSymbolicLink() && readdirSync(outputRoot).length === 0;
  } catch {
    return false;
  }
}

function block(reason) {
  reasons.push(reason);
  if (!runIdPattern.test(runId) || output !== outputRelative) {
    console.error(`E2E journey blocked: ${reason}`);
    process.exitCode = 2;
    return;
  }
  if (!outputIsAvailable()) {
    console.error(`E2E journey blocked: the run output already contains data or is not a plain directory; it was left unchanged (${reason}).`);
    process.exitCode = 2;
    return;
  }
  try {
    writeResult('blocked', reason);
  } catch {
    console.error(`E2E journey blocked: ${reason}`);
  }
  console.error(`E2E journey blocked: ${reason}`);
  process.exitCode = 2;
}

function walkFiles(directory, prefix = '') {
  if (!existsSync(directory)) return [];
  const rows = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) rows.push(...walkFiles(absolute, relative));
    else if (entry.isFile()) rows.push({ absolute, relative });
  }
  return rows;
}

function artifactPath(artifact) {
  if (artifact?.kind !== 'screenshot' || artifact.mediaType !== 'image/png' || artifact.redaction !== 'complete' || typeof artifact.path !== 'string') {
    return undefined;
  }
  const raw = safeRelative(artifact.path);
  if (!raw) return undefined;
  const candidates = raw.startsWith('results/') ? [raw] : [`results/${raw}`, raw];
  for (const candidate of candidates) {
    const safe = safeRelative(candidate);
    if (!safe) continue;
    const absolute = path.resolve(outputRoot, safe);
    if (!absolute.startsWith(`${outputRoot}${path.sep}`)) continue;
    try {
      if (!statSync(absolute).isFile()) continue;
      const bytes = readFileSync(absolute);
      if (bytes.length < 8 || !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) continue;
      return safe;
    } catch {
      // Try the next report-relative candidate.
    }
  }
  return undefined;
}

if (!runIdPattern.test(runId)) {
  block('EWM_E2E_RUN_ID is missing or invalid.');
} else if (output !== outputRelative) {
  block('EWM_E2E_OUTPUT must be a new runner-local .runs/<run-id> directory.');
} else if (!outputIsAvailable()) {
  console.error('E2E journey blocked: the requested output directory already contains data or is not a plain directory; no files were changed.');
  process.exitCode = 2;
} else {
  try {
    if (!manifestPath) throw new Error('EWM_E2E_SETUP_STATE is missing.');
    manifest = JSON.parse(readFileSync(path.resolve(cwd, manifestPath), 'utf8'));
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new Error('Setup manifest is not an object.');
    if (manifest.testmap_run_id !== runId) throw new Error('Setup manifest run ID does not match this output run.');
    if (process.env.EWM_E2E_FIXTURE_NAME !== manifest.fixture_name) throw new Error('Fixture name does not match the setup manifest.');
    if (process.env.EWM_E2E_EXPECT_TEXT !== manifest.expected_text) throw new Error('Expected watermark text does not match the setup manifest.');
    if (device !== 'emulator-5554') throw new Error('Only emulator-5554 is allowed for this journey.');
    if (modelId !== 'cursor/auto') throw new Error('Only the authorized Magpie model cursor/auto is allowed.');
    const endpoint = new URL(baseURL);
    if (endpoint.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(endpoint.hostname)) {
      throw new Error('The configured Magpie endpoint must be loopback-local.');
    }
    if (typeof manifest.fixture_name !== 'string' || !manifest.fixture_name || path.basename(manifest.fixture_name) !== manifest.fixture_name) {
      throw new Error('Setup manifest fixture name is invalid.');
    }
    if (typeof manifest.fixture_uri !== 'string' || !manifest.fixture_uri.startsWith('content://media/')) {
      throw new Error('Setup manifest source URI is not a MediaStore fixture URI.');
    }
    if (typeof manifest.fixture_sha256 !== 'string' || !/^[a-fA-F0-9]{64}$/.test(manifest.fixture_sha256)) {
      throw new Error('Setup manifest fixture SHA-256 is invalid.');
    }
    if (typeof manifest.marker !== 'string' || !manifest.marker || typeof manifest.expected_text !== 'string' || manifest.expected_text.length < 6 || manifest.expected_text.length > 96 || /[\u0000-\u001f]/.test(manifest.expected_text)) {
      throw new Error('Setup manifest marker or expected watermark text is invalid.');
    }
  } catch (error) {
    block(error instanceof Error ? error.message : 'Setup manifest validation failed.');
  }

  if (process.exitCode === undefined) {
    mkdirSync(path.dirname(outputRoot), { recursive: true });
    mkdirSync(outputRoot, { recursive: true });
    const childEnv = {
      ...process.env,
      E2E_TELEMETRY_DISABLED: '1',
      EWM_E2E_BASE_URL: baseURL,
      EWM_E2E_MODEL: modelId,
      AGENT_DEVICE_STATE_DIR: path.resolve(cwd, '.e2e', 'agent-device', runId),
    };
    const cli = path.join(cwd, 'node_modules', 'e2e', 'dist', 'cli', 'bin.js');
    const args = [
      cli,
      'run',
      '--config', 'e2e.config.ts',
      '--output', outputRelative,
      '--reporter', 'list',
      '--trace', 'on',
      '--no-cache',
      'tests/android-edit-export.e2e.ts',
    ];

    try {
      const child = spawnSync(process.execPath, args, { cwd, env: childEnv, stdio: 'inherit' });
      childExitCode = child.status ?? undefined;
      childSignal = child.signal ?? undefined;
      if (child.error) reasons.push(`E2E process could not start: ${child.error.message}`);
      if (childExitCode !== 0) reasons.push(`E2E process exited with code ${childExitCode ?? 'unknown'}.`);
    } catch (error) {
      reasons.push(`E2E process could not start: ${error instanceof Error ? error.message : 'unknown error'}.`);
      childExitCode = 1;
    }

    try {
      report = JSON.parse(readFileSync(path.join(outputRoot, 'report.json'), 'utf8'));
    } catch {
      reasons.push('The native E2E report is missing or unreadable.');
    }

    const selected = report?.run?.results?.filter((result) => result.selected && result.kind === 'test') ?? [];
    if (selected.length !== 1) reasons.push(`Expected exactly one selected journey test; found ${selected.length}.`);
    const result = selected[0];
    const attempt = result?.attempts?.[0];
    const steps = attempt?.steps ?? [];
    if (result?.status !== 'passed' || attempt?.status !== 'passed') reasons.push('The journey test did not pass in the native report.');

    modelProof = normalizeAgentModelEvidence(steps, {
      expectedProvider: 'magpie.chat',
      expectedModel: modelId,
      expectedActCount: 3,
      expectedAssertCount: 1,
      configuredEndpoint: baseURL,
    });
    actualModel = modelProof.model;
    if (!modelProof.verified) {
      reasons.push('Each live agent.act and agent.assert step must record Magpie magpie.chat cursor/auto model calls, with the configured loopback endpoint recorded separately.');
    }

    const attempts = result?.attempts ?? [];
    const allArtifacts = attempts.flatMap((item) => item.artifacts ?? []);
    const artifactById = new Map(allArtifacts.map((artifact) => [artifact.id, artifact]));
    const screenshotByStep = screenshotsByStep(steps, artifactById, artifactPath);
    for (const label of ['journey-edited', 'journey-after-export']) {
      if (!namedScreenshotPath(steps, screenshotByStep, label)) {
        reasons.push('The native report is missing the step-associated ' + label + ' PNG.');
      }
    }
    const traceFiles = walkFiles(path.join(outputRoot, 'results'), 'results')
      .filter((entry) => entry.relative.endsWith('/trace.md'))
      .map((entry) => safeRelative(entry.relative))
      .filter(Boolean);
    tracePath = traceFiles[0];
    if (!tracePath || !existsSync(path.join(outputRoot, tracePath))) reasons.push('The native Markdown trace is missing.');

    stepRows = steps.map((step) => ({
      text: step.label || step.api || step.kind,
      state: step.status,
      ...(screenshotByStep.get(step.id) ? { screenshot: screenshotByStep.get(step.id) } : {}),
      duration_ms: step.durationMs,
    }));
    const status = normalizeRunStatus({ nativeStatus: report?.run?.status, attempts, reasons });
    writeResult(status, reasons.length ? reasons.join(' ') : undefined);
    process.exitCode = status === 'passed' ? 0 : status === 'blocked' ? 2 : childExitCode || 1;
  }
}
