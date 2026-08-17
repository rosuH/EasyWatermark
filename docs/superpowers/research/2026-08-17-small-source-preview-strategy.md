# 小图预览策略（低分辨率 / 聊天压缩图）

**日期：** 2026-08-17  
**范围：** 用户选中小图（例如聊天压缩约 480×320）时，在约 1012×1330 的编辑预览里发糊，产品该怎么做。  
**不在范围：** 实现、上线文案定稿、ML/云端放大、改 ADR-0018 的「预览 ≡ 导出」引擎、以及官方页上找不到却臆造的竞品功能。

**已经定过（除非你本人重开，否则不翻案）：**

- 预览 ≡ 导出：水印按**图像空间像素**烤进图里，再 **Fit 绘制**。禁止「预览锐、导出糊」的屏幕空间撒谎。（[ADR-0018](../../adr/0018-option-c2-common-raster-android-export.md)；`PreviewResolutionPolicy` KDoc。）
- 完全离线、无追踪、无 ML/云端 SDK。（`AGENTS.md` / `docs/CONTEXT.md` 隐私不变量。）
- 批量胶片条 + **一份**会话水印配置。
- 导出今天保持**源图像素尺寸**（不偷偷改尺寸）。（导出脊柱与测试：合成/保存宽度等于解码源图宽度。）
- 水印尺寸在图像空间：`fontPx = textSize * imageWidth / REF_WIDTH`，`REF_WIDTH = 1000`。（`WatermarkGeometry.kt`。）

**证据等级：** **P** = 一手（官方帮助、厂商站、商店页、平台 HIG、本仓库）。**U** = 搜过官方面仍未知（不从评测或三方博客推断）。

480×320 是**你举的**聊天压缩图例子。本笔记**不声称**这是微信官方输出尺寸；未找到微信对该尺寸的一手规格。

---

## 1. 仓库现状（EasyWatermark 今天）

| 事实 | 代码 / 文档怎么说 | 来源 |
|---|---|---|
| 默认 `textSize` | `14f`（`WatermarkConfigRules.DEFAULT_TEXT_SIZE`；`WaterMark.default` 相同）。滑条上限 100；存储只夹下限（`≥ 1`）。 | `WatermarkConfigRules.kt`、`WaterMark.kt` **P** |
| 图像空间字号 | `fontPx(textSize, imageWidth) = textSize * imageWidth / 1000`。宽 480、默认 14 时，位图里的字是 **6.72 px**。 | `WatermarkGeometry.kt` **P** |
| 导出偏好 | `UserPreferences`：`outputFormat`（JPEG/PNG）、`compressLevel`（默认 80，按 20 对齐，最小 20），以及 Android 相册 / 跟图主题开关。**没有输出宽高、没有放大、没有 DPI。** | `UserPreferences.kt`、`UserConfigRepository.kt` **P** |
| 导出页 UI | 只有格式 + 质量（`SaveExportOptionsSection`）。 | `SaveExportSheetShell.kt` / `SaveExportOptionsSection.kt` **P** |
| 导出尺寸 | Desktop/iOS 测试断言：打水印后/保存后的宽度等于解码源图宽度。预览策略：最终导出是「全源图（不受这些边界约束）」。 | `DesktopRealImageDecodeGoldenTest.kt`、`IosWatermarkRendererTest.kt`、`PreviewResolutionPolicy.kt` **P** |
| 预览 Fit | 已提交解码是「够当前显示上 `ContentScale.Fit` 不把软图再放大」。源图**已经小于** Fit 矩形时，解码**保持原像素（不放大）**。软是源图限制——KDoc 举例「480px 样本放到 2k 预览框」。 | `PreviewResolutionPolicy.kt` **P** |
| 尺寸相关文案 | 默认英文 `strings.xml` 没有低分辨率 / 模糊 / 太小 / 分辨率警告。最接近的是 `tips_need_compress_img`（「存储不足或图片太大」）——讲的是**太大 / OOM**，且**没有** Kotlin/Swift 调用方。导出错误会提 OOM（「少选几张或选小一点的图」），不提小图。`ImageInfo.width/height` 在会话模型上有，产品 UI 不展示。 | `shared/.../values/strings.xml`、`ImageInfo.kt`、仓库检索 **P** |
| 词汇漂移 | `docs/CONTEXT.md` 仍把 `textSize` 写成「预览尺度的 view-px」。**代码是图像空间**（`WatermarkGeometry`）。本题以几何源码为准。 | `CONTEXT.md` vs `WatermarkGeometry.kt` **P** |

演算（物理，不是产品主张）：480×320 Fit 进 1012×1330 的框，缩放为 `min(1012/480, 1330/320) ≈ 2.11`。显示约 **1012×675**。每个源像素约占 2.1 个屏幕像素。默认水印字按 **6.72 px** 栅格化，再随照片 Fit。导出仍是 **480×320**，字还是 6.72 px。预览发糊**就是**导出。

---

## 2. 证据表

行为码：**warn** = 告诉用户源图相对*显示/印刷目标*太小；**upscale** = 能放大像素尺寸（传统和/或 ML）；**resize-down** = 能为投递缩小；**don’t-enlarge** = 有缩放但拒绝造像素；**silent** = 官方面不提低分辨率警告或自动放大；**block** = 拒绝继续；**U** = 官方面上没找到。

| 产品 | 行为 | 官方面 | URL |
|---|---|---|---|
| **EasyWatermark（今天）** | 对小图沉默；**没有**导出缩放/放大；预览 Fit 会把原像素位图撑大 | 本仓库 | 本文 §1 |
| **eZy Watermark Photos** | 对*照片*做 **resize**（裁切/比例）+「不同画质」；商店文案写 “without losing image resolution”；**未找到**低分辨率警告；resize 会不会放大 **U** | Play 页；厂商站（高分辨率 + 画质导出） | [Play](https://play.google.com/store/apps/details?id=com.whizpool.ezywatermarklite&hl=en_US) **P**；[ezywatermark.com](https://www.ezywatermark.com/) **P**；[App Store](https://apps.apple.com/us/app/ezy-watermark-photos/id494473910) **P** |
| **iWatermark+** | 把 **resize & filter & export options** 列为水印/输出工具；“Prepare output sizes while watermarking”；**未找到**低分辨率警告；移动端 resize 会不会放大 **U** | App Store；Plum Amazing 产品页 | [App Store](https://apps.apple.com/us/app/iwatermark-watermark-add-logo/id931231254) **P**；[plumamazing.com/iwatermark-plus-ios](https://plumamazing.com/iwatermark-plus-ios) **P** |
| **iWatermark Pro（桌面）** | **resize**（「6 大选项」）、缩略图、按尺寸/分辨率/格式**过滤输入**；“Scale images to a consistent output size”；营销页**未找到**低分辨率*警告*；默认是否 Don’t Enlarge **U** | 厂商站 | [Pro Mac](https://plumamazing.com/iwatermark-pro) **P**；[Pro Windows](https://plumamazing.com/iwatermark-pro-2-windows) **P** |
| **PhotoMarks（Win/Mac）** | 水印前的 Transform 滤镜做 **resize-down**；明确 **Don’t upscale images that are smaller than specified size**；resize 帮助里没有低分辨率*警告* | 官方帮助 + 功能页 | [Resize filter](https://photomarks.app/help/filters_transform_resize.htm) **P**；[features](https://photomarks.app/features.html) **P** |
| **PhotoMarks（iOS）** | 商店文案：“Watermark Photos with No Quality Loss”；胶片条；列表里**没有** resize 或低分辨率警告 | App Store | [App Store](https://apps.apple.com/us/app/photomarks-watermark-photos/id616003945) **P** |
| **Watermark X**（Watermark Studio X，id 1100583565） | 模板、颜色/透明度/大小/位置；对低分辨率和导出缩放 **silent** | App Store | [App Store](https://apps.apple.com/us/app/watermark-watermark-maker-x/id1100583565) **P** |
| **Add Watermark – Batch Process** | 批量、预览、模板；对低分辨率和导出缩放 **silent** | App Store | [App Store](https://apps.apple.com/us/app/add-watermark-batch-process/id1401798417) **P** |
| **My Watermarks** | Logo/签名制作；水印*图章*可缩放；对源图低分辨率和导出放大 **silent** | App Store | [App Store](https://apps.apple.com/us/app/my-watermarks/id1190230890) **P** |
| **Snapseed** | 按**原图分辨率**保存，仅当超过**设备上限**才降采样；帮助里展示文件名 / 文件大小 / 图像尺寸；官方帮助**没有**低分辨率警告或放大 | Google 帮助 | [resolution](https://support.google.com/snapseed/answer/3118134?hl=en) **P**；[file size / device caps](https://support.google.com/snapseed/answer/6202870?hl=en) **P** |
| **Lightroom（Enhance）** | **ML 放大**：Super Resolution = 线性 2× / 像素 4×，新 DNG，仅一次；显式 Enhance，不是静默 | Adobe 帮助 | [Enhance](https://helpx.adobe.com/lightroom/desktop/edit-photos/enhance-details.html) **P** |
| **Lightroom Classic（导出）** | 可选 **Resize to Fit**；**Don’t Enlarge** 会忽略会放大的宽高 | Adobe 帮助 | [Export to disk](https://helpx.adobe.com/lightroom-classic/desktop/export-photos/export-files-disk-or-cd.html) **P** |
| **Photoshop** | `Image > Image Size` 的 **Resample** 会增删像素；Adobe 写明上采样会**变差**，因为程序必须猜像素；另有 **Generative Upscale**（2×/4×，Firefly/Topaz）是显式 AI 命令 | Adobe 帮助 | [Resample / quality](https://helpx.adobe.com/photoshop/kb/advanced-cropping-resizing-resampling-photoshop.html) **P**；[Generative Upscale](https://helpx.adobe.com/photoshop/desktop/repair-retouch/clean-restore-images/enhance-image-quality-with-generative-upscale.html) **P** |
| **Pixelmator Pro** | 显式 **Super Resolution**（ML），Image Size 算法含 Super Resolution；不是静默 | Apple 托管用户指南 | [Super Resolution](https://support.apple.com/guide/pixelmator-pro/automatically-increase-image-resolution-pix1d6f0eac3/mac) **P**；[Image Size](https://support.apple.com/guide/pixelmator-pro/resize-an-image-pix1db05dd71/mac) **P** |
| **Apple 照片（Mac）** | 导出可选 **Size**（含 Full Size / 更小 / Custom）；**Export Unmodified Original** 保持导入像素；**没有** Super Resolution；导出指南**没有**低分辨率警告 | Apple 支持 | [Export](https://support.apple.com/guide/photos/export-photos-videos-and-slideshows-pht6e157c5f/mac) **P** |
| **Google 相册** | 编辑 → 保存 / 另存副本；Pixel **Zoom Enhance** 是机型门控 ML，并保存**副本**；官方编辑帮助**没有**低分辨率导入警告 | Google 帮助 | [Edit photos](https://support.google.com/photos/answer/6128850) **P**；[Zoom Enhance](https://support.google.com/photos/answer/9940184) **P** |
| **Canva** | 印刷：结账校对时若图达不到该产品所需分辨率会 **warn**；建议 300 DPI；小图在版面里*放小*而不是撑满；另有 **AI Upscale**（输入 10×10…25 MP，最大 50 MP，仅一次） | Canva 帮助 | [Print looks blurry](https://www.canva.com/help/print-looks-blurry/) **P**；[Upscale](https://www.canva.com/help/upscale-image/) **P**；[Quality check / Template Assistant](https://www.canva.com/help/quality-check-for-design/) **P**（仅 Teams 助手） |
| **Shutterfly** | 低于建议印刷尺寸时 **warn**；「网上看着也许还行」但印刷差；**用原图，不要用社交媒体文件**；“Increasing resolution with software may degrade image quality”；帮助正文**不拦**下单 | Shutterfly 帮助 | [Photo quality guidelines](https://shutterfly.my.site.com/helpcenter/s/article/maximizing-photo-resolution-expert-tips) **P** |
| **Walgreens Photo（合作方 API）** | 按产品的**最小像素**表（4×6 = **540×360**）；低于下限时购物车应标 **invalid** / 警告；有 300 DPI 说明 | Walgreens Developer | [Image requirements](https://developer.walgreens.com/support/image-requirements) **P** |
| **Minted** | 放入的照片相对所选印刷设计分辨率不够时 **warn**（符号）；仍可下单；结果「可能像素化或模糊」 | Minted 帮助 | [Low-resolution photos](https://help2.minted.com/articles/General/Low-Resolution-Photos) **P** |
| **Apple HIG — Images** | App **素材** 的倍率（@2x/@3x）；测试避免素材像素化；**没有**对*用户选的*低分辨率照片做警告的指引 | HIG | [Images](https://developer.apple.com/design/human-interface-guidelines/images) **P** |
| **Material / Android 图像** | App **资源** 密度分桶；栅格图缩放会丢细节；**没有** Material 3 对用户照片低分辨率警告的模式（m3.material.io 检索：无） | Android Design | [Images and graphics](https://developer.android.com/design/ui/mobile/guides/layout-and-content/images-graphics) **P** |

**这是规律，不是投票：** 消费级水印 App 在官方面上大多 **silent**。桌面水印工作室提供**可选缩放**，至少 PhotoMarks 用复选框**拒绝放大**。相邻*编辑器*默认保住源像素（Snapseed、Apple 照片 Full Size），除非用户自己选缩放或**显式 ML Enhance**。印刷/图库产品对照*已知输出尺寸*做 **warn**，并告诉人不要造像素。

---

## 3. 物理 vs 策略

### 物理（与产品立场无关）

1. **位图有固定像素网格。** 把 480×320 画进约 1012 宽的 Fit 矩形就是放大。插值（双线性 / Lanczos / 最近邻）补不回 JPEG 已经丢掉的频率。视网膜框上发软，是 `ContentScale.Fit` 的预期结果，不是解码失败。（`PreviewResolutionPolicy` 已写过这个情况。）
2. **预览解码不是元凶。** 策略已经规定：源图小于 Fit 矩形时**不放大解码**。480 px 的图以 480 px *位图*存在，再由合成器撑开。把预览位图做成 1012 宽，只是把这次拉伸**提前烤进去**（一样软，更费内存）。
3. **水印字也受源图限制。** 默认 `textSize` 14 在 480 宽上是 **6.72 px**。这是诚实的图像空间尺寸。屏幕空间「锐字」会在编辑器里显得比 480×320 导出更大更清——ADR-0018 禁止这种撒谎。
4. **对聊天 JPEG 做传统放大**是在猜像素。Adobe 自己的 Image Size 文档：上采样会变差，因为程序必须猜要加哪些像素；「最好在导入时就用正确分辨率」。Shutterfly：用软件提高分辨率可能降低画质；把像素拉大会让印刷发虚。
5. **ML Super Resolution** 是市面上唯一*有时*更锐的路径，找到的每一处官方实例都是**显式、有名字、常常只能一次、云端或端侧 ML** 的命令（Lightroom Enhance、Photoshop Generative Upscale、Pixelmator Super Resolution、Canva Upscale、Pixel Zoom Enhance）。那是另一类产品。

### 策略（只对 EasyWatermark）

| 约束 | 对这件事的含义 |
|---|---|
| 预览 ≡ 导出（ADR-0018） | 不能只在屏幕上画锐水印（或美化过的放大图）。 |
| 离线 / 无 ML SDK | 不能做 Lightroom / Canva / Pixelmator 那种 Enhance。 |
| 一份会话配置 + 图像空间 `textSize` | 按图静默放大只会改小图的 `fontPx`（`14 * 960/1000` vs `14 * 480/1000`），混合胶片条就不再共享同一视觉尺度。 |
| 导出 = 源图尺寸 | 静默放大破坏现有契约，也破坏「选什么就得到什么」的隐私叙事（不暗改用户文件）。 |
| 批量 | 40 张里有 1 张小图就用对话框拦住导出，是敌意；角标 / 列表行不是。 |
| 隐私话术 | 用户本来就不信任聊天压缩图；有用的动作是「有原图就用原图」，不是「我们造了几百万像素」。 |

**一句话：** 预览已经在说实话。产品缺口是**解释**，不是**像素**。

---

## 4. 策略选项（对本 App）

### A — 继续沉默（现状）

保持 Fit 预览、源尺寸导出、不加任何说明。

- **隐私 / 离线：** 不变。  
- **所见即所得：** 已经成立；框很大，用户仍可能*觉得*被骗。  
- **批量：** 零额外 UX。  
- **代价：** 「导出糊了」的支持会一直来。  
- **贴近竞品：** 大多数移动端水印商店页。

### B — 说明、不拦截、不放大 *（推荐）*

当选中/聚焦照片的**源图长边**（或百万像素）低于阈值，给一条不拦截的说明：这张比预览框小，所以看起来软；**导出一致**；有原图就换原图。导出仍是源尺寸。

- **隐私 / 离线：** 不新增 SDK；尺寸已在 `ImageInfo` 上，可选展示。  
- **所见即所得：** 加固 ADR-0018，而不是遮过去。  
- **批量：** 胶片条角标 + 导出列表提示；不中断任务。  
- **类比：** Shutterfly / Canva Print / Minted（警告，继续）；Snapseed（展示 Image Size）。  
- **风险：** 阈值太高会啰嗦（很多手机截图、老相机）。

### C — 永远显示 `宽×高`（不带警告语气）

编辑器或导出行上安静写「480×320」。不下判断。

- 比 B **更轻**；有人连不上「尺寸 → 发糊」。  
- 适合当 B 的**配套**，单独做**偏弱**。

### D — 以后再做可选导出缩放（Lightroom / PhotoMarks 形态）

在格式/质量之外加可选长边（或「适应框」），**Don’t Enlarge 默认开**。为网页投递缩小；**绝不**放大，除非你以后单独接受「放大（会变软）」控件。

- **隐私：** 仍离线；只做传统重采样。  
- **所见即所得：** 若打开缩放，预览必须按**导出**像素合成（ADR-0018），否则这个控件在撒谎。  
- **批量 + 图像空间尺寸：** 统一目标长边能把混合源图收成同一投递尺寸——这才是桌面水印软件做 resize 的原因。这是**新的产品职责**，不是「这张 480 px 发糊」的修复。  
- **现在不当默认：** 没有偏好字段、没有文案、你也没提出投递缩放。

### E — 对本 App 否决（不要选）

| 做法 | 为什么不 |
|---|---|
| 屏幕空间锐水印 / 只放大预览 | 违反 ADR-0018。 |
| 静默导出放大 | 违反源尺寸契约；混合批量 `fontPx` 漂移；Shutterfly / Adobe 都说软件放大会变差。 |
| 默认 ML Super Resolution | 违反离线 / 无 ML 政策；每一处 **P** 实例都是具名 Enhance，不是导入默认。 |
| 拦截小图导出 | 没有水印竞品 **P** 这么做。印刷店警告是因为*他们*有物理尺寸；EW 的输出*就是*源图。Walgreens 式「invalid」针对的是**已选印刷 SKU**，EW 没有这个。 |

---

## 5. 推荐默认

**做 B：不拦截的、讲源尺寸的说明。不放大。不改导出尺寸。不拆开预览和导出。**

为什么不是「看情况」：

1. 发软是 **正确的物理**，已经写在 `PreviewResolutionPolicy` 里。传统加像素不加细节；ML 加像素则越权。  
2. 官方水印竞品要么 **沉默**，要么提供**可以拒绝放大的可选缩放**（PhotoMarks Don’t upscale；Lightroom Don’t Enlarge）。移动端水印商店官方页既没找到 **warn**，也没找到 **自动放大**。EW 的差异化是诚实（离线、剥 EXIF、预览 ≡ 导出）——一行说明合品牌；静默放大不合。  
3. 印刷/图库的 **P** 来源，才是「大预览之后失望」的正确类比：他们 **warn**，让你用**原文件**，并说软件放大**更糟**。他们不静默重采样。  
4. Snapseed——官方帮助里最接近的**离线、单图、保像素**编辑器——保持原分辨率，只在设备上限处降采样。  
5. 一份会话 `textSize` 只有在每张图都按**自己的**像素网格打水印时才自洽。模型已经如此；不要为了让微信缩略图看起来像相机原图而拆掉它。

**默认阈值（调研建议，不是上线数字）：** 当**源图长边小于已提交的 Fit 长边**（1:1 填不满框）视为「小」；或更简单的 v1：长边 **&lt; 720**（现有草稿/占位桶）**或**低于约 0.3 MP（480×320 = 0.15 MP）。480×320 也**低于 Walgreens 4×6 下限（540×360）**——只作行业类比，不是 EW 的印刷 SKU。数字由你拍（§6）。

**文案方向（不是最终字符串）：** 定性 + 尺寸（「这张是 480×320。预览更大，所以看起来软。导出仍是 480×320。」）。不要写「锐 N%」，不要写「Enhance」。若上线，默认英文双写。

### 什么情况下改推荐

| 若变成这样 | 则改成 |
|---|---|
| 你要把 EW 做成**投递/缩放**工具（网页长边、邮件、批量统一输出） | 加 **D**，Don’t Enlarge 默认开；预览必须用导出像素。仍不静默放大。 |
| 隐私政策允许你维护的**端侧** Super Resolution 模型 | 可选、显式、**按图** Enhance，新 DNG/副本，永不作为导入默认。新开 ADR。 |
| 支持数据表明用户**总有**原图且无视说明 | 去掉 B；只做 **C**（永远 `宽×高`）可能够。 |
| 支持数据表明用户**从没有**原图，警告是噪音 | 维持 **A**，或只做 **C**。 |
| 你接受**屏幕空间**编辑器（字锐、导出糊） | 重开 ADR-0018；本笔记的推荐不再适用。 |

---

## 6. 仍要你拍的（不是再调研）

1. **阈值：** 相对预览框（跟显示走）还是绝对（720 / 1000 / 百万像素）？相对显示在 Desktop 双栏更诚实；绝对在批量更简单。  
2. **何时出现：** 导入 Toast、聚焦时编辑器芯片、胶片条角标、导出页行，还是组合？（Library Read 升级已经教过「挑选时对话框，不要一条常驻条」。这次同还是不同？）  
3. **批量：** 任一张小就警告一次，还是按图？480 + 4000 混在一起是难点。  
4. **动作：** 只有「换一张」，还是再加空操作「继续」？微信之后设备上常常**没有**原图。  
5. **每张都显示 `宽×高`**（C 作底）还是只在警告时显示（B）？  
6. **以后的 D：** 导出长边是不是今年就要的产品，和这次发糊投诉无关？  
7. **分享进来：** 微信/其他聊天来源是否和选择器照片同一套？（像素一样；命中率可能更高。）

---

## 7. 来源（一手）

- EasyWatermark：`WatermarkGeometry.kt`、`WatermarkConfigRules.kt`、`WaterMark.kt`、`UserPreferences.kt`、`UserConfigRepository.kt`、`PreviewResolutionPolicy.kt`、`SaveExportOptionsSection.kt`、`shared/.../values/strings.xml`、`docs/adr/0018-option-c2-common-raster-android-export.md`、`docs/CONTEXT.md`
- eZy：https://play.google.com/store/apps/details?id=com.whizpool.ezywatermarklite&hl=en_US ；https://www.ezywatermark.com/ ；https://apps.apple.com/us/app/ezy-watermark-photos/id494473910
- iWatermark+：https://apps.apple.com/us/app/iwatermark-watermark-add-logo/id931231254 ；https://plumamazing.com/iwatermark-plus-ios
- iWatermark Pro：https://plumamazing.com/iwatermark-pro ；https://plumamazing.com/iwatermark-pro-2-windows
- PhotoMarks：https://photomarks.app/help/filters_transform_resize.htm ；https://photomarks.app/features.html ；https://apps.apple.com/us/app/photomarks-watermark-photos/id616003945
- Watermark X：https://apps.apple.com/us/app/watermark-watermark-maker-x/id1100583565
- Add Watermark：https://apps.apple.com/us/app/add-watermark-batch-process/id1401798417
- My Watermarks：https://apps.apple.com/us/app/my-watermarks/id1190230890
- Snapseed：https://support.google.com/snapseed/answer/3118134?hl=en ；https://support.google.com/snapseed/answer/6202870?hl=en
- Lightroom Enhance：https://helpx.adobe.com/lightroom/desktop/edit-photos/enhance-details.html
- Lightroom Classic 导出：https://helpx.adobe.com/lightroom-classic/desktop/export-photos/export-files-disk-or-cd.html
- Photoshop 重采样：https://helpx.adobe.com/photoshop/kb/advanced-cropping-resizing-resampling-photoshop.html
- Photoshop Generative Upscale：https://helpx.adobe.com/photoshop/desktop/repair-retouch/clean-restore-images/enhance-image-quality-with-generative-upscale.html
- Pixelmator Pro：https://support.apple.com/guide/pixelmator-pro/automatically-increase-image-resolution-pix1d6f0eac3/mac ；https://support.apple.com/guide/pixelmator-pro/resize-an-image-pix1db05dd71/mac
- Apple 照片导出：https://support.apple.com/guide/photos/export-photos-videos-and-slideshows-pht6e157c5f/mac
- Google 相册：https://support.google.com/photos/answer/6128850 ；https://support.google.com/photos/answer/9940184
- Canva：https://www.canva.com/help/print-looks-blurry/ ；https://www.canva.com/help/upscale-image/ ；https://www.canva.com/help/quality-check-for-design/
- Shutterfly：https://shutterfly.my.site.com/helpcenter/s/article/maximizing-photo-resolution-expert-tips
- Walgreens：https://developer.walgreens.com/support/image-requirements
- Minted：https://help2.minted.com/articles/General/Low-Resolution-Photos
- Apple HIG Images：https://developer.apple.com/design/human-interface-guidelines/images
- Android 图像/图形：https://developer.android.com/design/ui/mobile/guides/layout-and-content/images-graphics
