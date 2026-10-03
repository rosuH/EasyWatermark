# Retained source notices

The build reads LICENSE, COPYING and NOTICE files from installed packages that contribute rendered JavaScript. It also includes Tailwind's generated CSS and Vite's own module-preload helper and its generated CommonJS helper. The CommonJS helper retains the complete shared Rollup Plugins MIT section from installed Vite's LICENSE.md (including RollupJS Plugin Contributors copyright), while Vite's own helper retains its core MIT notice. Full original notices are HTML-escaped and embedded in the single offline `dist/index.html`, under “Third-party licenses / 第三方许可”. Unknown missing package licenses fail the build; no network access is required.

Two checked-in upstream notices cover source outside those packaged files:

- `shadcn-ui.LICENSE.txt`: local Button follows the shadcn/ui pattern; MIT, copyright 2023 shadcn. Source: https://github.com/shadcn-ui/ui/blob/main/LICENSE.md (retrieved 2026-10-03).
- `radix-primitives.LICENSE.txt`: `@radix-ui/react-compose-refs@1.1.2` publishes no LICENSE file. Its package manifest declares MIT and the Radix Primitives repository. This is the monorepo MIT license, copyright 2022 WorkOS, identical to installed `@radix-ui/react-slot@1.2.3/LICENSE`. Verified against https://github.com/radix-ui/primitives/blob/main/LICENSE on 2026-10-03. The fallback is restricted to the exact missing package version; future versions must be reviewed if still missing.

These notices preserve attribution. They do not constitute a FOSSA scan result or resolve scanner findings by themselves.
