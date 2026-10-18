// Bounded visual bridge for PHPicker's missing remote AX cells, on an owned empty simulator only.
import { readFile, writeFile, mkdir, readdir, copyFile, lstat } from "node:fs/promises";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { grokCall } from "./testmap_launch_about_pilot.mjs";

const exec = promisify(execFile);
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");

export function pickerNodes(answer, imageHash, width, height) {
  if (!answer || !["picker", "loading", "app"].includes(answer.scene)) throw new Error("invalid picker scene");
  if (!Array.isArray(answer.controls) || answer.controls.length > 16 || !Array.isArray(answer.texts) || answer.texts.length > 16) throw new Error("invalid visual picker controls");
  const nodes = answer.controls.map((c, index) => {
    if (typeof c.label !== "string" || !c.label.trim() || c.label.length > 500 ||
        !["photo", "confirm", "cancel", "dismiss", "other"].includes(c.kind) ||
        ![c.x, c.y, c.width, c.height].every(Number.isFinite) || c.x < 0 || c.y < 70 ||
        c.width <= 0 || c.height <= 0 || c.x + c.width > width || c.y + c.height > height) throw new Error("invalid visual picker bounds");
    return { index, ref: `vpp${index}`, type: "Button", role: "button", label: c.label,
      identifier: `iosPicker-${c.kind}-${index}`, bundleId: "visual-ios-picker", visual: true, imageHash,
      rect: { x: c.x, y: c.y, width: c.width, height: c.height } };
  });
  for (const text of answer.texts) {
    if (typeof text !== "string" || !text.trim() || text.length > 500) throw new Error("invalid picker text");
    nodes.push({ index: nodes.length, type: "StaticText", label: text, visual: true, imageHash });
  }
  return nodes; // Visual descriptions never supply native selected/enabled/checked facts.
}

export async function createIOSPicker({ client, modules, model, signal, outputDir, udid }) {
  const state = JSON.parse(await readFile(process.env.TESTMAP_IOS_PICKER_STATE, "utf8"));
  const owned = JSON.parse(await readFile(process.env.TESTMAP_IOS_OWNED_DEVICE, "utf8"));
  if (!udid || !owned.created_for_this_run || !owned.name?.startsWith("EWM-Hybrid-Owned-") || owned.udid !== udid || owned.udid !== state.udid ||
      !state.prepared || state.platform !== "ios" || state.baseline_ids?.length !== 0 || !state.source?.id ||
      !Array.isArray(state.baseline_tmp)) throw new Error("visual picker requires a freshly owned empty simulator");
  const source = await readFile(state.source.path);
  if (hash(source) !== state.source.sha256) throw new Error("owned picker fixture changed");
  const trace = { source: "grok-screenshot-candidates", nativeSelection: "unknown", observations: [] };
  let armed = false, cached;
  const save = () => writeFile(path.join(outputDir, "ios-picker-vision-trace.json"), JSON.stringify(trace, null, 2));
  const imagePath = path.join(outputDir, "ios-picker-current.png");
  const pixels = async () => {
    await client.capture.screenshot({ path: imagePath, pixelDensity: 1 });
    const bytes = await readFile(imagePath);
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20);
    if (width < 200 || width > 2000 || height < 300 || height > 3000) throw new Error("unexpected picker screenshot size");
    const { stdout } = await exec("magick", [imagePath, "-crop", `${width}x${height - 70}+0+70`, "+repage", "rgba:-"], { encoding: "buffer", maxBuffer: 32 * 1024 * 1024, signal });
    return { bytes, width, height, imageHash: hash(stdout) };
  };
  return {
    trace,
    imagesFor(snapshot) {
      if (!cached || snapshot.visualImageHash !== cached.imageHash) throw new Error("visual context no longer matches its screenshot");
      return [cached.bytes, source];
    },
    noteAction(target) {
      if (target.identifier === "launchPickImageButton" || target.label === "Choose images anyway") armed = true;
    },
    async capture(native) {
      if (!armed) return native;
      if (native.nodes.some((n) => n.type === "Alert" || n.role === "alert")) return native;
      if (native.nodes.some((n) => n.identifier === "PUPickerUnavailableView" || n.type === "ActivityIndicator")) return native;
      if (native.nodes.some((n) => n.identifier === "sharedComposeSaveButton")) { armed = false; return native; }
      if (native.nodes.some((n) => n.label === "Choose images anyway")) return native;
      let frame = await pixels();
      if (!cached || cached.imageHash !== frame.imageHash) {
        let stable = 0;
        for (let attempt = 0; attempt < 10 && stable < 2; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 200));
          const next = await pixels(); stable = next.imageHash === frame.imageHash ? stable + 1 : 0; frame = next;
        }
        if (stable < 2) throw new Error("picker pixels did not settle");
        const record = { imageHash: frame.imageHash };
        trace.observations.push(record); await save();
        const shot = `ios-picker-observation-${trace.observations.length}.png`;
        await writeFile(path.join(outputDir, shot), frame.bytes); record.screenshot = shot;
        const answer = await grokCall({ modules, model, signal, record, images: [frame.bytes, source],
          name: "picker_controls", maxOutputTokens: 3000,
          description: "Describe only controls actually visible in the FIRST screenshot. The SECOND image is the owned synthetic photo to locate, never UI. No actions. Bounds are logical screenshot pixels. Do not invent native selection facts.",
          state: { width: frame.width, height: frame.height,
            task: "Identify the real iOS PHPicker overlay, its close/cancel and top-right confirm/checkmark buttons, and the one photo matching the SECOND image. Label that photo 'Owned synthetic photo (blue top, white center, gold bottom)'. Include other visible buttons only if useful. Exclude underlying app controls. If no picker is visible use app; a loading picker is loading. Omit photo control if no match. A checkmark icon can be labeled 'Confirm selected photos'." },
          schema: { type: "object", additionalProperties: false, properties: {
            scene: { type: "string", enum: ["picker", "loading", "app"] },
            texts: { type: "array", maxItems: 16, items: { type: "string", maxLength: 500 } },
            controls: { type: "array", maxItems: 16, items: { type: "object", additionalProperties: false, properties: {
              label: { type: "string", maxLength: 500 }, kind: { type: "string", enum: ["photo", "confirm", "cancel", "dismiss", "other"] },
              x: { type: "number" }, y: { type: "number" }, width: { type: "number" }, height: { type: "number" },
            }, required: ["label", "kind", "x", "y", "width", "height"] } },
          }, required: ["scene", "texts", "controls"] },
        });
        cached = { ...frame, scene: answer.scene, nodes: pickerNodes(answer, frame.imageHash, frame.width, frame.height) };
        await save();
      }
      if (cached.scene === "app") { armed = false; return native; }
      return { ...native, nodes: cached.nodes, visualSource: trace.source, visualImageHash: cached.imageHash };
    },
    async preserveOutput() {
      const dir = path.join(outputDir, "ios-app-output"); await mkdir(dir, { recursive: true });
      const tmp = path.join(state.container, "tmp"), files = [];
      for (const name of await readdir(tmp)) {
        if (!/^ewm_(?:out|src)_[A-Za-z0-9_-]+\.(?:png|jpe?g)$/i.test(name) || state.baseline_tmp.includes(name)) continue;
        const from = path.join(tmp, name), stat = await lstat(from);
        if (!stat.isFile() || stat.isSymbolicLink()) throw new Error("unexpected iOS output file");
        await copyFile(from, path.join(dir, name)); files.push({ name, sha256: hash(await readFile(from)) });
      }
      if (!files.some((f) => f.name.startsWith("ewm_out_"))) throw new Error("missing real iOS pipeline output before close");
      await writeFile(path.join(dir, "manifest.json"), JSON.stringify(files, null, 2));
    },
  };
}

if (path.resolve(process.argv[1] || "") === fileURLToPath(import.meta.url) && process.argv.includes("--self-check")) {
  const { default: assert } = await import("node:assert/strict");
  const answer = { scene: "picker", texts: ["Photos"], controls: [{ label: "Own photo", kind: "photo", x: 0, y: 345, width: 134, height: 134 }] };
  const nodes = pickerNodes(answer, "hash", 402, 874);
  assert.equal(nodes[0].selected, undefined); assert.equal(nodes[0].visual, true);
  for (const patch of [{ y: 0 }, { x: 400 }, { width: -1 }, { kind: "shell" }]) assert.throws(() => pickerNodes({ ...answer, controls: [{ ...answer.controls[0], ...patch }] }, "hash", 402, 874));
  console.log("iOS picker bounds/provenance guards passed");
}
