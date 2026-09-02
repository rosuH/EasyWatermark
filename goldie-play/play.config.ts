/**
 * Play Store phone tiles. Not a GoldieConfig — goldie only knows iPhone 6.9"
 * and 17-pro bezels. This file documents the Play pipeline next to the
 * scripts that actually capture and compose.
 *
 * Copy / theme / scene list: ../goldie/shared.ts
 * Studio preview: goldie studio (localhost:4321) → top bar Play Store.
 * Listing UI lives in ./studio/; framed tiles in ./out/.
 */
export const play = {
  width: 1080,
  height: 1920,
  avd: "Pixel_9_Pro_XL",
  packageId: "me.rosuh.easywatermark.debug",
  activity: "me.rosuh.easywatermark.ui.MainActivity",
  density: 420,
};
