import { createOpenAI } from '@ai-sdk/openai';
import { mobile } from '@e2e-dev/mobile';
import type { E2EConfig } from 'e2e';

const runId = process.env.EWM_E2E_RUN_ID;
const baseURL = process.env.EWM_E2E_BASE_URL ?? 'http://127.0.0.1:3425/v1';
const modelId = process.env.EWM_E2E_MODEL ?? 'cursor/auto';
const device = process.env.EWM_E2E_DEVICE ?? 'emulator-5554';
const scenario = process.env.EWM_E2E_SCENARIO ?? 'android-edit-export';

if (!runId || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(runId)) {
  throw new Error('EWM_E2E_RUN_ID must be a safe unique run identifier.');
}
if (modelId !== 'cursor/auto') {
  throw new Error('This journey is pinned to the authorized Magpie model cursor/auto.');
}
if (scenario !== 'android-edit-export' && scenario !== 'android-large-image-preview') {
  throw new Error('EWM_E2E_SCENARIO is not an allowed Android journey.');
}

const magpie = createOpenAI({
  name: 'magpie',
  baseURL,
  apiKey: 'magpie',
});

export default {
  tests: [scenario === 'android-large-image-preview'
    ? 'tests/android-large-image-preview.e2e.ts'
    : 'tests/android-edit-export.e2e.ts'],
  targets: [
    {
      name: 'android',
      engine: mobile({
        platform: 'android',
        device,
        session: `ewm-e2e-${runId}`,
        snapshot: 'full',
      }),
      app: { bundleId: 'me.rosuh.easywatermark.debug' },
      trace: 'on',
    },
  ],
  agents: {
    default: {
      model: magpie.chat(modelId),
      maxSteps: 35,
      maxModelCalls: 35,
      system: 'When a screen target contains interactive children, prefer the actionable child or button over the parent group. If an action fails, re-observe and choose the current actionable child.',
    },
  },
  workers: 1,
  retries: 0,
  timeout: scenario === 'android-large-image-preview' ? 300_000 : 600_000,
  launchTimeout: 90_000,
  actionTimeout: 45_000,
  cleanupTimeout: 45_000,
  cache: 'off',
  trace: 'on',
} satisfies E2EConfig;
