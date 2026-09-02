/**
 * Marketing copy and strip rhythm shared by App Store (goldie.config.ts)
 * and Play (goldie-play/). Device, bezel, capture, and output size stay
 * in the platform config — this file is store-agnostic.
 */
export const APP_ROOT = "/Users/rosu/Coding/EasyWatermark";

/** rosuH/goldie checkout: Play studio extras + pad/remux/frame scripts. */
export const GOLDIE_HOME =
  process.env.GOLDIE_HOME ?? `${APP_ROOT}/../goldie`;

export const locales = ["en-US", "zh-CN"] as const;

export const theme = {
  background: "linear-gradient(180deg, #1c1c0f 0%, #131309 46%, #0d0d07 100%)",
  headlineColor: "#FFF9EF",
  subheadColor: "#C4BFA8",
  fontFamily:
    '"PingFang SC", "SF Pro Display", -apple-system, system-ui, sans-serif',
  copyHeightRatio: 0.2,
  deviceWidthRatio: 0.78,
  template: "magazine" as const,
  layout: "classic" as const,
  screenOnly: false,
};

export const store = {
  name: "Easy Watermark",
  subtitle: {
    "en-US": "Stamp photos. Stay offline.",
    "zh-CN": "盖上用途，离线更安全",
  },
  developer: "rosuH",
  category: "Photo & Video",
  rating: 4.8,
  ratingCount: "1.2K Ratings",
  ageRating: "4+",
  price: "Free",
  description: {
    "en-US":
      "EasyWatermark stamps a purpose on your photos so they can't be reused against you.\n\n" +
      "When you need it: sending an ID for a visa, bank or rental; publishing original work; " +
      "sharing contracts; listing items — a tiled mark that survives crops and reposts.\n\n" +
      "100% offline. No analytics, crash SDKs, or ads. Fill and stroke, presets plus a picker, " +
      "opacity, angle, spacing. Templates and batch JPEG/PNG. Export strips EXIF.",
    "zh-CN":
      "EasyWatermark 给照片盖上「用途」，让别人没法拿去二次使用。\n\n" +
      "办证、开户、租房要传证件——盖上「仅供申办之用」。发原创、合同、二手商品图——" +
      "半透明署名平铺全图，转发裁剪都去不掉。\n\n" +
      "100% 离线，无统计、无崩溃上报、无广告。填充描边、七色预设、模板、批量 JPEG/PNG。导出抹除 EXIF。",
  },
};

export type SharedScreenshotScene = {
  id: string;
  /** Argent flow name (iOS goldie). Play capture uses `seed` instead. */
  flow: string;
  /** Android / iOS `-storeSeedScene` value. Award has none (poster). */
  seed?: string;
  layout?: "minimal" | "poster";
  headline: Record<string, string>;
  subhead: Record<string, string>;
};

export const screenshotScenes: SharedScreenshotScene[] = [
  {
    id: "work",
    flow: "store-01-editor-tiled",
    seed: "photo",
    headline: {
      "en-US": "Ship the work, [[keep the credit]]",
      "zh-CN": "作品发出去，[[署名跟着走]]",
    },
    subhead: {
      "en-US": "Tiled across the image. Credit remains after every repost.",
      "zh-CN": "平铺覆盖全图，转载后署名仍在",
    },
  },
  {
    id: "award",
    flow: "store-06-award",
    layout: "poster",
    headline: {
      "en-US": "Offline-grade [[reliability]]",
      "zh-CN": "断网级别的[[安全可靠]]",
    },
    subhead: {
      "en-US": "Offline. Open source. No ads.",
      "zh-CN": "离线。开源。无广告。",
    },
  },
  {
    id: "style",
    flow: "store-02-style-color",
    seed: "style",
    headline: {
      "en-US": "Fill, stroke, [[your style]]",
      "zh-CN": "笔画级的[[样式控制]]",
    },
    subhead: {
      "en-US": "Weight, italics, outline — make the mark yours",
      "zh-CN": "填充、描边、粗体斜体——你的水印，你的风格",
    },
  },
  {
    id: "idcard",
    flow: "store-07-idcard",
    seed: "idcard",
    headline: {
      "en-US": "Uploading an ID? [[Say why]]",
      "zh-CN": "办证上传？[[写明用途]]",
    },
    subhead: {
      "en-US": "“For this application only” — useless anywhere else",
      "zh-CN": "「仅供申办之用」——被挪用也无处可用",
    },
  },
  {
    id: "color",
    flow: "store-02-style-color",
    seed: "color",
    headline: {
      "en-US": "Any color, [[exactly yours]]",
      "zh-CN": "七色预设，[[随手取色]]",
    },
    subhead: {
      "en-US": "Presets for speed, a full picker for precision",
      "zh-CN": "白黑亮黄一键换，取色盘精确到一个色值",
    },
  },
  {
    id: "layout",
    flow: "store-03-layout",
    seed: "layout",
    headline: {
      "en-US": "Density and angle, [[easy to adjust]]",
      "zh-CN": "密度角度，[[随手可调]]",
    },
    subhead: {
      "en-US": "Spacing and angle sliders, live on your photo",
      "zh-CN": "水平垂直间距实时预览，铺满每一个角落",
    },
  },
  {
    id: "template",
    flow: "store-04-templates",
    seed: "templates",
    headline: {
      "en-US": "Save it once, [[reuse forever]]",
      "zh-CN": "常用水印，[[存成模板]]",
    },
    subhead: {
      "en-US": "Templates for signatures, IDs and internal docs",
      "zh-CN": "签名、证件用途——一键套用，不再重打",
    },
  },
  {
    id: "export",
    flow: "store-05-export",
    seed: "export",
    headline: {
      "en-US": "Export on [[your terms]]",
      "zh-CN": "格式质量，[[由你决定]]",
    },
    subhead: {
      "en-US": "JPEG or PNG, a quality dial, your folder, in batch",
      "zh-CN": "JPEG / PNG、质量滑杆、自选文件夹，批量导出",
    },
  },
];

/** Magazine slot, then per-scene overrides (award full-bleed; color not title-less). */
export const sceneLayouts: Record<string, string> = {
  award: "poster",
  color: "offset",
};

export const MAGAZINE_SEQUENCE = [
  "offset",
  "copy-below",
  "tilt-right",
  "hero",
  "minimal",
] as const;

export function layoutForScene(id: string, index: number): string {
  return sceneLayouts[id] ?? MAGAZINE_SEQUENCE[index % MAGAZINE_SEQUENCE.length];
}
