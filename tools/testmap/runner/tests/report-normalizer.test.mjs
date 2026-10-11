import assert from 'node:assert/strict';
import test from 'node:test';

import {
  namedScreenshotPath,
  normalizeAgentModelEvidence,
  normalizeRunStatus,
  screenshotsByStep,
} from '../report-normalizer.mjs';

test('normalizes native agent APIs without treating provider-default as the configured endpoint', () => {
  const model = {
    provider: 'magpie.chat',
    model: 'cursor/auto',
    endpoint: 'provider-default',
    calls: 2,
  };
  const steps = [
    { id: 'select', kind: 'act', api: 'agent.act', model, metrics: { modelCalls: 2 } },
    { id: 'style', kind: 'agent', api: 'agent.act', model, metrics: { modelCalls: 2 } },
    { id: 'assert', kind: 'assert', api: 'agent.assert', model, metrics: { modelCalls: 1 } },
    { id: 'export', kind: 'act', api: 'agent.act', model, metrics: { modelCalls: 2 } },
  ];

  const proof = normalizeAgentModelEvidence(steps, {
    expectedProvider: 'magpie.chat',
    expectedModel: 'cursor/auto',
    expectedActCount: 3,
    expectedAssertCount: 1,
    configuredEndpoint: 'http://127.0.0.1:3425/v1',
  });

  assert.equal(proof.verified, true);
  assert.equal(proof.native_endpoint, 'provider-default');
  assert.equal(proof.configured_endpoint, 'http://127.0.0.1:3425/v1');
  assert.equal(proof.configured_endpoint_valid, true);
  assert.equal(proof.calls, 8);
  assert.deepEqual(proof.agent_steps.map((step) => step.api), [
    'agent.act',
    'agent.act',
    'agent.assert',
    'agent.act',
  ]);
  assert.ok(proof.agent_steps.every((step) => step.provider_model_verified));
});

test('binds named PNG evidence only to the app.screenshot step that owns its artifact', () => {
  const steps = [
    { id: 'edit', api: 'app.screenshot', label: 'journey-edited', artifacts: ['edit-png'] },
    { id: 'export', api: 'app.screenshot', label: 'journey-after-export', artifacts: ['export-png'] },
  ];
  const artifactById = new Map([
    ['edit-png', { kind: 'screenshot', mediaType: 'image/png', redaction: 'complete', path: 'results/edit.png' }],
    ['export-png', { kind: 'screenshot', mediaType: 'image/png', redaction: 'complete', path: 'results/export.png' }],
  ]);
  const screenshots = screenshotsByStep(steps, artifactById, (artifact) =>
    artifact?.kind === 'screenshot' && artifact?.mediaType === 'image/png' &&
    artifact?.redaction === 'complete' ? artifact.path : undefined,
  );

  assert.equal(namedScreenshotPath(steps, screenshots, 'journey-edited'), 'results/edit.png');
  assert.equal(namedScreenshotPath(steps, screenshots, 'journey-after-export'), 'results/export.png');
  assert.equal(namedScreenshotPath(steps, new Map(), 'journey-edited'), undefined);
});

test('rejects mismatched native model identity and non-loopback configured endpoints', () => {
  const proof = normalizeAgentModelEvidence([
    {
      id: 'act',
      api: 'agent.act',
      kind: 'act',
      model: { provider: 'other.chat', model: 'cursor/auto', endpoint: 'provider-default', calls: 1 },
    },
    {
      id: 'assert',
      api: 'agent.assert',
      kind: 'assert',
      model: { provider: 'magpie.chat', model: 'cursor/auto', endpoint: 'provider-default', calls: 1 },
    },
  ], {
    expectedProvider: 'magpie.chat',
    expectedModel: 'cursor/auto',
    expectedActCount: 1,
    expectedAssertCount: 1,
    configuredEndpoint: 'https://example.invalid/v1',
  });

  assert.equal(proof.verified, false);
  assert.equal(proof.configured_endpoint_valid, false);
  assert.equal(proof.agent_steps[0].provider_model_verified, false);
});


test('classifies a started STEP_TIMEOUT as failed even when the native run is blocked', () => {
  assert.equal(normalizeRunStatus({
    nativeStatus: 'blocked',
    attempts: [{ status: 'failed', startedAt: '2026-10-11T16:23:02.695Z', error: { code: 'STEP_TIMEOUT' } }],
    reasons: ['The journey test did not pass.'],
  }), 'failed');
  assert.equal(normalizeRunStatus({ nativeStatus: 'blocked', attempts: [], reasons: ['No test was selected.'] }), 'blocked');
});
