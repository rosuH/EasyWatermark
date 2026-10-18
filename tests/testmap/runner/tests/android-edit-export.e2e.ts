import { test } from '@e2e-dev/mobile';

const fixtureName = process.env.EWM_E2E_FIXTURE_NAME;
const expectedText = process.env.EWM_E2E_EXPECT_TEXT;

if (!fixtureName || !expectedText) {
  throw new Error('The backend must provide a fixture name and unique expected watermark text.');
}

const selectionBudget = { timeout: 180_000, maxSteps: 14, maxModelCalls: 8 };
const editAndStyleBudget = { timeout: 240_000, maxSteps: 14, maxModelCalls: 14 };
const exportBudget = { timeout: 120_000, maxSteps: 14, maxModelCalls: 8 };

test('Android edits and exports one synthetic text watermark', async ({ app, agent }) => {
  await app.open();

  const selected = await agent.act(
    'Use the Android system Photo Picker. Inspect its actual screenshot and select only the newly added run-owned fixture ' +
      JSON.stringify(fixtureName) +
      ' by its visible blue, white, yellow horizontal bands. Other existing photos and old exports may appear; do not guess by list order or choose another/personal image. After selecting A, confirm with the picker Add (1) button. Stop when EasyWatermark shows the selected image in its editor. Prefer an actionable button/child over a containing group; if a tap fails, re-observe and target the actionable child.',
    selectionBudget,
  );
  if (selected.modelCalls < 1 || selected.actions < 1) {
    throw new Error('The selection step must make a live model call and execute device actions.');
  }

  const edited = await agent.act(
    'Keep the selected image. Create a text watermark with this exact text: ' +
      JSON.stringify(expectedText) +
      '. Set its typeface to Bold and its style to Stroke/Outline. Stop when the exact text and both settings are visible in the editor.',
    editAndStyleBudget,
  );
  if (edited.modelCalls < 1 || edited.actions < 1) {
    throw new Error('The edit and style step must make a live model call and execute device actions.');
  }

  await agent.assert(
    'The editor shows the run-owned synthetic image with three horizontal bands in blue, white, and yellow order, and the exact watermark text ' +
      JSON.stringify(expectedText) +
      ', Bold typeface, and Stroke/Outline style. Inspect the screen pixels and visible controls; do not infer any value.',
    { vision: true, timeout: 45_000 },
  );
  await app.screenshot('journey-edited');

  const exported = await agent.act(
    'Use the app normal Save flow to export the current edited image as PNG: choose PNG in the output-format options, then save it to the album. Keep the selected image, exact text, Bold typeface, and Stroke/Outline style unchanged. Do not use a share shortcut or another image source. Stop only after the app confirms export completed.',
    exportBudget,
  );
  if (exported.modelCalls < 1 || exported.actions < 1) {
    throw new Error('The export step must make a live model call and execute device actions.');
  }

  await app.screenshot('journey-after-export');
});
