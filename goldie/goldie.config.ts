import type { GoldieConfig } from "/Users/rosu/n/lib/node_modules/goldie/dist/config.d.ts";
import {
  APP_ROOT,
  GOLDIE_HOME,
  locales,
  screenshotScenes,
  store,
  theme,
} from "./shared.ts";

const config: GoldieConfig & {
  studio?: { extras?: Array<{ url: string; dir: string }>; inject?: string[] };
} = {
  appRoot: APP_ROOT,
  appPath: `${APP_ROOT}/build/ios_goldie_dd/Build/Products/Release-iphonesimulator/iosApp.app`,
  bundleId: "me.rosuh.easywatermark.ios",

  devices: ["iphone-6.9"],
  locales: [...locales],
  appearance: "dark",

  frame: { variant: "17-pro-orange" },

  theme: {
    ...theme,
    // Studio Design can switch this (goldie.design.json). Only `award`
    // keeps a scene.layout so the trust card stays full-bleed.
  },

  store,

  studio: {
    extras: [
      { url: "/play", dir: `${GOLDIE_HOME}/studio/play` },
      { url: "/play", dir: `${APP_ROOT}/goldie-play/studio` },
      { url: "/play", dir: `${APP_ROOT}/goldie-play/out` },
    ],
    inject: ["/play/switch.js"],
  },

  scenes: [
    ...screenshotScenes.map((scene) => ({
      kind: "screenshot" as const,
      id: scene.id,
      flow: scene.flow,
      ...(scene.layout ? { layout: scene.layout } : {}),
      headline: scene.headline,
      subhead: scene.subhead,
    })),
    {
      kind: "preview",
      id: "preview",
      // One live clip. capture-store.sh records while store-preview-tour
      // taps Style / color / Layout / spacing / Save — not three stills.
      segments: [{ id: "tour", flow: "store-preview-tour", holdSeconds: 0 }],
    },
  ],
};

export default config;
