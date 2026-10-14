import { test } from '@e2e-dev/mobile';

const fixtureName = process.env.EWM_E2E_FIXTURE_NAME;

if (!fixtureName) {
  throw new Error('The backend must provide the run-owned large-image fixture name.');
}

test('Android opens the run-owned oversized image in preview', { timeout: 300_000 }, async ({ app, agent }) => {
  await app.open();

  const opened = await agent.act(
    'Use the Android system Photo Picker. Inspect the actual picker screen and select only the run-owned test image named ' +
      JSON.stringify(fixtureName) +
      '. The source is a 49861 × 1350 PNG with no text or watermark. The Photo Picker may crop it into a square, soft thumbnail, so do not expect the thumbnail to look wide. If filenames are visible, match this exact filename. Otherwise, inspect the actual pixels and identify the thumbnail with a thin dark-blue band at the top, a broad plain white center, and a gold/yellow band at the bottom; it has no text or watermark. Do not guess from list order or recency. If you cannot identify this exact image with confidence, stop and fail without selecting a different or personal photo. Tap the picker Add (1) button. Stop when EasyWatermark displays this image in its preview. Do not edit, style, or export it. If the app closes, freezes, shows a crash screen, or returns to the launcher before the preview appears, do not relaunch the app, retry, or choose another image; let the step fail.',
    { timeout: 180_000, maxSteps: 14, maxModelCalls: 8 },
  );

  if (opened.modelCalls < 1 || opened.actions < 2) {
    throw new Error('The preview step must use a live model and include image selection plus Add (1) device actions.');
  }
});
