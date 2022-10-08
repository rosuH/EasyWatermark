<h1 align="center">简单水印</h1>

<p align="center">
  <img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/rosuh/easywatermark">
  &nbsp;
  &nbsp;
  <a href="https://hosted.weblate.org/engage/easywatermark/zh_Hans/">
    <img src="https://hosted.weblate.org/widgets/easywatermark/zh_Hans/svg-badge.svg" alt="翻译状态" />
  </a>
  &nbsp;
  &nbsp;
  <a href="https://app.fossa.com/projects/git%2Bgithub.com%2FrosuH%2FEasyWatermark?ref=badge_small" alt="FOSSA Status">
    <img src="https://app.fossa.com/api/projects/git%2Bgithub.com%2FrosuH%2FEasyWatermark.svg?type=small"/>
  </a>
</p>
<p align="center">  
安全、简单、快速地为你的敏感照片添加水印，防止被小人泄露、利用。
  </br>
尤其适合因不可抗力而需上传的：（手持）身份证照、隐私证件照、早期小样图片、带版权图片等照片。
</p>
</br>

<p align="center">
<img src="/static/preview.png"/>
</p>

> 当然，也适用于制作表情包，毕竟支持图片水印，效果十分鬼畜。
<a href="#" align="right"><img src="https://i.loli.net/2020/08/26/A53u6UbKZPYCv7t.jpg" width="5%"></a>

<p align="left">
<a href="https://play.google.com/store/apps/details?id=me.rosuh.easywatermark"><img src="/static/google-play-badge_cn.png" width="auto" height="64px"/></a>
  <a href="https://www.coolapk.com/apk/272743"><img src="/static/logo_coolapk.png" width="auto" height="64px"/></a>
    <a href="https://f-droid.org/packages/me.rosuh.easywatermark/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" width="auto" height="64px"/></a>
</p>


## 特性

> 当时我就念了一首诗 👓

- 单纯离线本地应用，代码开源想看就看
- 横竖间距均可调节，颜色明暗随心转换
- 大小角度自由旋转，文字图片皆可打上
- 水印重复全图铺满，坏蛋除水印有点难

> 正经地说（推了推眼镜）

- **安全性**：
  - 代码完全开源，使用宽松的 MIT 协议，您可以随意 Fork 自己修改并删除您认为有问题的代码 ;)
  - 不涉及网络请求，不申请网络请求权限，不用担心自己的照片被泄露。API >= 29 的用户甚至不需要申请任何权限。(28 及其以下用户需要申请存储权限以访问和存储照片)
  - 当然也没有统计、埋点和 Device ID，甚至没有崩溃上报（所以如果遇到崩溃，麻烦和我们分享一下崩溃信息吧 >_<）。
  - 我们放弃方便的第三方采集 SDK 以及各种统计信息，就是为了让你能放心地使用。**你的就是你的。**
- **布局**：支持水印间的横竖间隔，自动重复铺满整张图片。
- **样式**：字体颜色、文本样式、不透明度、大小和旋转角度都可以调节。
- **内容**：支持文字水印和图片水印。

## 下载

开发者所主导的下载渠道：
- [GitHub Release](https://github.com/rosuH/EasyWatermark/releases)：永远保持最新
- [Google Play](https://play.google.com/store/apps/details?id=me.rosuh.easywatermark)
    - 🍺 注意：收费版，但代码一致，如果您愿意请作者喝一杯茶（或者您比较任性），那么请去此下载，否则请选择其他渠道:)
- [F-Droid](https://f-droid.org/packages/me.rosuh.easywatermark/)
- [酷安](https://www.coolapk.com/apk/272743)

其他渠道均非开发者所主导，请仔细甄别后下载使用。

## 如何使用？
你想怎么用就怎么用。比较适合于需要提交证件照、手持证件照或敏感照片等情况。例如：
- 国内各种实名制，动不动就上传身份证正反照片，甚至是手持证件照。
- 项目前期预览图、小样、版权或单纯的恶搞图片

参考文案：
> 本照片仅供 xx 作 xx 之用，他用无效。

可以把不透明度调低，不要挡住关键信息即可，一般审核都没问题。

> （目前）我们（暂时）无法阻止信息被上传，甚至（暂时）无法阻止信息被小人泄漏，但我们可以降低被泄漏信息的价值。
>
> 即便最后（可能）没什么用，起码可以恶心泄漏者一把。

## UI
- 由驰名海内外（👏🤪）的 UI 大佬 [@tovi](https://www.figma.com/@tovi) 操刀设计
  - 凡是你觉得不好用的，都是我 UI 还原不行，和 UI 稿无关。XD
  
> 此APP由[@tovi](https://www.figma.com/@tovi)设计，因此UI和相关设计资源的所有权利均归他所有，未经任何人或组织的允许，不得使用。

## 开源许可
使用到的第三方库：

- [daniel-stoneuk/material-about-library](https://github.com/daniel-stoneuk/material-about-library)
- [skydoves/ColorPickerView](https://github.com/skydoves/ColorPickerView)
- [material-components/material-components-android](https://github.com/material-components/material-components-android)
- [Compressor](https://github.com/zetbaitsu/Compressor/)
- 示例图片来自 Jeremy Bishop
  on [Unsplash](https://unsplash.com/s/photos/animals?utm_source=unsplash&utm_medium=referral&utm_content=creditCopyText)

## 反馈与贡献

我们非常欢迎您在 issues 区发表您任何意见与建议，或者直接提交 PR 以贡献您的代码。 当然您也可以直接联系开发者的 Telegram 或邮件，我们将择期回复。

## 本地化

我们非常欢迎您提供任何方式的本地化帮助，包括但不限于直接参与代码编写、创建 issue、邮件等，或者我们也推荐您使用 [WebLate](https://hosted.weblate.org/engage/easywatermark/) 平台提供翻译帮助，谢谢！

<a href="https://hosted.weblate.org/engage/easywatermark/zh_Hans/">
<img src="https://hosted.weblate.org/widgets/easywatermark/zh_Hans/88x31-grey.png" alt="翻译状态" />
</a>

## [隐私政策](https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md)

如果你需要的话。
