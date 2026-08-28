<p align="center">
  <img src="static/app-icon.png" alt="Easy Watermark" width="96" height="96">
</p>

<h1 align="center">Easy Watermark</h1>

<p align="center">
  <strong>No network. That's safer.</strong>
</p>

<p align="center">
  <a href="./README.md">English</a> · <a href="./README_zh-CN.md">简体中文</a>
</p>

<p align="center">
  <img alt="Latest release" src="https://img.shields.io/github/v/release/rosuh/easywatermark">
  &nbsp;
  <img alt="License" src="https://img.shields.io/github/license/rosuH/EasyWatermark">
  &nbsp;
  <a href="https://hosted.weblate.org/engage/easywatermark/en/">
    <img src="https://hosted.weblate.org/widgets/easywatermark/en/svg-badge.svg" alt="Translation status">
  </a>
</p>

<p align="center">
  Securely, easily watermark sensitive photos.<br>
  For ID uploads, proofs, and images that should not be reused.
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/featureGraphic.png" alt="Easy Watermark — no network, that's safer" width="880">
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=me.rosuh.easywatermark"><img src="static/google-play-badge.png" alt="Get it on Google Play" height="64"></a>
  <a href="https://f-droid.org/packages/me.rosuh.easywatermark/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="64"></a>
  <a href="https://www.coolapk.com/apk/272743"><img src="static/logo_coolapk.png" alt="Get it on Coolapk" height="64"></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" alt="Ship the work, keep the credit" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" alt="Offline-grade reliability" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" alt="Your shot, your name on it" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" alt="Uploading an ID? Say why" width="210">
</p>
<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" alt="Fill, stroke, your style" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" alt="Any color, exactly yours" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" alt="Density and angle, easy to adjust" width="210">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" alt="Save it once, reuse forever" width="210">
</p>

## Features

- **Text and image marks** — a signature, a logo, or a short purpose line.
- **Style** — color, fill or stroke, typeface, size, opacity, and angle. Live on the photo.
- **Layout** — horizontal and vertical spacing, plus tile or single placement.
- **Templates** — save a line once and reuse it on the next batch.
- **Batch** — pick several photos, keep the same mark, export them together.
- **Clean export** — JPEG or PNG. All EXIF, including location, is stripped.

## Usage

For ID photos, proofs, and other images you have to send. A line such as *This photo is for XX, for XXX only* is enough. Keep opacity low so the important part stays readable.

## Privacy

Easy Watermark does not collect analytics, device IDs, or crash reports. There is no third-party tracking SDK.

On Android 10 and later, choosing a photo is the only permission the app needs. Android 9 and below still require storage access to read and save images.

See the [Privacy Policy](PrivacyPolicy.md).

## Get the app

Use a developer-run channel:

| Channel | Notes |
|---|---|
| [GitHub Releases](https://github.com/rosuH/EasyWatermark/releases) | Latest APK |
| [Google Play](https://play.google.com/store/apps/details?id=me.rosuh.easywatermark) | Paid edition, same code — supports ongoing development |
| [F-Droid](https://f-droid.org/packages/me.rosuh.easywatermark/) | Free, reproducible build |
| [Coolapk](https://www.coolapk.com/apk/272743) | Listed for users in China |

Listings elsewhere are unofficial. Check the package name `me.rosuh.easywatermark` before you install.

## Open source

The app is [MIT](LICENSE) licensed. Issues and pull requests are welcome. Translations go through [Weblate](https://hosted.weblate.org/engage/easywatermark/).

Bugs that cannot be reported in-app (there is no crash reporter) can be mailed to [hi@rosuh.me](mailto:hi@rosuh.me).

## Credit

**Design**

- Interface by [@tovi](https://www.figma.com/@tovi). The UI and related design assets remain his — please do not reuse them without permission.

**Icons**

- [Phosphor Icons](https://phosphoricons.com/) — selected Regular-weight glyphs, vendored as Compose vectors. License notice in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

**Libraries**

- [Coil](https://github.com/coil-kt/coil) — gallery, filmstrip, and other UI thumbnails
- [Material Components for Android](https://github.com/material-components/material-components-android)
- [ColorPickerView](https://github.com/skydoves/ColorPickerView) — Android custom color dialog
