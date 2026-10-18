export function normalizeAgentModelEvidence(steps, options) {
  const configuredEndpoint = safeLoopbackEndpoint(options.configuredEndpoint);
  const agentSteps = (steps ?? [])
    .filter((step) => typeof step?.api === 'string' && step.api.startsWith('agent.'))
    .map((step) => {
      const model = step.model ?? {};
      const rawCalls = model.calls ?? step.metrics?.modelCalls ?? 0;
      const calls = Number.isFinite(rawCalls) && rawCalls > 0 ? rawCalls : 0;
      return {
        api: step.api,
        kind: step.kind,
        status: step.status,
        provider: model.provider,
        model: model.model,
        calls,
        native_endpoint: safeNativeEndpoint(model.endpoint),
        provider_model_verified: model.provider === options.expectedProvider && model.model === options.expectedModel && calls > 0,
      };
    });
  const actions = agentSteps.filter((step) => step.api === 'agent.act');
  const assertions = agentSteps.filter((step) => step.api === 'agent.assert');
  const verified =
    actions.length === options.expectedActCount &&
    assertions.length === options.expectedAssertCount &&
    agentSteps.length === options.expectedActCount + options.expectedAssertCount &&
    agentSteps.every((step) =>
      step.provider_model_verified &&
      step.native_endpoint === 'provider-default' &&
      step.status === 'passed',
    ) &&
    configuredEndpoint !== undefined;

  const nativeEndpoints = [...new Set(agentSteps.map((step) => step.native_endpoint).filter(Boolean))];
  const firstModelStep = agentSteps[0];
  return {
    provider: 'magpie',
    model: firstModelStep?.model ?? options.expectedModel,
    native_provider: firstModelStep?.provider,
    native_model: firstModelStep?.model,
    ...(nativeEndpoints.length === 1 ? { native_endpoint: nativeEndpoints[0] } : {}),
    configured_endpoint: configuredEndpoint,
    configured_endpoint_source: 'EWM_E2E_BASE_URL passed to createOpenAI({ baseURL }) in e2e.config.ts',
    configured_endpoint_valid: configuredEndpoint !== undefined,
    calls: agentSteps.reduce((total, step) => total + step.calls, 0),
    agent_steps: agentSteps,
    evidence_state: verified ? 'complete' : agentSteps.some((step) => step.calls > 0) ? 'partial' : 'none',
    verified,
  };
}

export function screenshotsByStep(steps, artifactById, resolvePath) {
  const result = new Map();
  for (const step of steps ?? []) {
    for (const artifactRef of step.artifacts ?? []) {
      const artifact = typeof artifactRef === 'string' ? artifactById.get(artifactRef) : artifactRef;
      const path = resolvePath(artifact);
      if (path) {
        result.set(step.id, path);
        break;
      }
    }
  }
  return result;
}

export function namedScreenshotPath(steps, screenshots, label) {
  const step = (steps ?? []).find((row) => row.api === 'app.screenshot' && row.label === label);
  return step ? screenshots.get(step.id) : undefined;
}

function safeLoopbackEndpoint(value) {
  try {
    const url = new URL(value);
    if (
      url.protocol !== 'http:' ||
      !['127.0.0.1', 'localhost'].includes(url.hostname) ||
      url.username || url.password || url.search || url.hash
    ) return undefined;
    return url.origin + url.pathname.replace(/\/+$/, '');
  } catch {
    return undefined;
  }
}

function safeNativeEndpoint(value) {
  if (value === 'provider-default') return value;
  if (typeof value !== 'string') return undefined;
  try {
    const url = new URL(value);
    if (url.username || url.password) return undefined;
    return url.origin + url.pathname.replace(/\/+$/, '');
  } catch {
    return undefined;
  }
}


export function normalizeRunStatus({ nativeStatus, attempts, reasons, scenario }) {
  const startedAttemptFailed = (attempts ?? []).some((attempt) =>
    attempt?.status === 'failed' && typeof attempt.startedAt === 'string'
  );
  if (scenario === 'android-large-image-preview' && startedAttemptFailed) return 'failed';
  const startedStepTimeout = (attempts ?? []).some((attempt) =>
    attempt?.status === 'failed' &&
    typeof attempt.startedAt === 'string' &&
    attempt.error?.code === 'STEP_TIMEOUT'
  );
  if (startedStepTimeout) return 'failed';
  if (nativeStatus === 'blocked') return 'blocked';
  return reasons.length === 0 && nativeStatus === 'passed' ? 'passed' : 'failed';
}
