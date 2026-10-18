import { readFileSync, readdirSync, existsSync } from "node:fs";
import { dirname, isAbsolute, join, parse } from "node:path";
import { createRequire } from "node:module";
import type { Plugin } from "vite";

const require = createRequire(import.meta.url);
const escapeHtml = (text: string) =>
  text
    .replace(/[ \t]+$/gm, "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");

function packageRoot(id: string): string | undefined {
  if (!isAbsolute(id) || !id.includes("node_modules/")) return;
  let dir = dirname(id.split("?")[0]);
  while (dir !== parse(dir).root) {
    const manifest = join(dir, "package.json");
    if (existsSync(manifest) && JSON.parse(readFileSync(manifest, "utf8")).name)
      return dir;
    dir = dirname(dir);
  }
}

export function thirdPartyLicenses(): Plugin {
  const roots = new Set<string>();
  let preload = false;
  let commonjs = false;
  let projectRoot = "";
  return {
    name: "testmap-third-party-licenses",
    apply: "build",
    configResolved(config) {
      projectRoot = config.root;
    },
    buildStart() {
      roots.clear();
      preload = false;
      commonjs = false;
    },
    renderChunk(_code, chunk) {
      for (const [id, module] of Object.entries(chunk.modules)) {
        if (module.renderedLength === 0) continue;
        if (id.includes("vite/modulepreload-polyfill")) preload = true;
        if (id === "\0commonjsHelpers.js") commonjs = true;
        const root = packageRoot(id);
        if (root) roots.add(root);
      }
      return null;
    },
    generateBundle: {
      order: "post",
      handler(_options, bundle) {
        // Tailwind's generated CSS is in the HTML, not the JS module graph.
        roots.add(dirname(require.resolve("tailwindcss/package.json")));
        if (preload) roots.add(dirname(require.resolve("vite/package.json")));
        const notices = [...roots].map((root) => {
          const pkg = JSON.parse(
            readFileSync(join(root, "package.json"), "utf8"),
          );
          const files = readdirSync(root)
            .filter((name) =>
              /^(licen[cs]e|copying|notice)([.-].*)?$/i.test(name),
            )
            .sort();
          // This exact published Radix package omits LICENSE; retain the
          // upstream monorepo license locally. Unknown missing licenses fail.
          if (
            !files.length &&
            pkg.name === "@radix-ui/react-compose-refs" &&
            pkg.version === "1.1.2"
          )
            return {
              title: `${pkg.name}@${pkg.version} (${pkg.license})`,
              text: readFileSync(
                join(projectRoot, "licenses/radix-primitives.LICENSE.txt"),
                "utf8",
              ).trim(),
            };
          if (!files.some((name) => /^(licen[cs]e|copying)/i.test(name)))
            this.error(`Missing bundled license: ${pkg.name}@${pkg.version}`);
          const text = files.map((name) => {
            let contents = readFileSync(join(root, name), "utf8").trim();
            // Only Vite's own generated preload helper ships in this page;
            // Vite's build-time bundled dependencies are not browser code.
            if (pkg.name === "vite" && name === "LICENSE.md")
              contents = contents
                .split("\n# Licenses of bundled dependencies")[0]
                .trim();
            return `${name}\n\n${contents}`;
          });
          return {
            title: `${pkg.name}@${pkg.version} (${pkg.license || "see license"})`,
            text: text.join("\n\n"),
          };
        });
        if (commonjs) {
          const viteLicense = readFileSync(
            join(dirname(require.resolve("vite/package.json")), "LICENSE.md"),
            "utf8",
          );
          const helperLicense = viteLicense
            .split("\n## ")
            .find((section) =>
              section.split("\n")[0].includes("@rollup/plugin-commonjs"),
            );
          if (!helperLicense)
            this.error(
              "Missing generated CommonJS helper license in Vite distribution",
            );
          notices.push({
            title: "@rollup/plugin-commonjs generated helper (MIT)",
            text: helperLicense.trim(),
          });
        }
        notices.push({
          title: "shadcn/ui Button pattern (MIT)",
          text: readFileSync(
            join(projectRoot, "licenses/shadcn-ui.LICENSE.txt"),
            "utf8",
          ).trim(),
        });
        notices.sort((a, b) =>
          a.title < b.title ? -1 : a.title > b.title ? 1 : 0,
        );
        const markup = `\n<details id="third-party-licenses" style="margin:12px 20px;font-size:12px"><summary>Third-party licenses / 第三方许可</summary>${notices.map((notice) => `\n<section><h2>${escapeHtml(notice.title)}</h2><pre style="white-space:pre-wrap;overflow-wrap:anywhere">${escapeHtml(notice.text)}</pre></section>`).join("")}\n</details>\n`;
        const html = bundle["index.html"];
        if (!html || html.type !== "asset" || typeof html.source !== "string")
          this.error("Cannot retain third-party licenses: index.html missing");
        html.source = html.source.replace("</body>", `${markup}</body>`);
        this.info(
          `Retained ${notices.length} bundled license notices in index.html`,
        );
      },
    },
  };
}
