# Compose Migration Smoke Checklist

目标：在 Compose 迁移期间保护关键用户路径，确保入口、选图、编辑、返回和导出相关行为在每个里程碑后都可快速复核。

## 冷启动
Name: 冷启动
Entry: 系统桌面
Steps:
1. 点击应用图标启动应用。
2. 等待首屏稳定渲染完成。
Expected:
- 进入 LaunchScreen。
- 页面可见 `choose image` 主按钮。
- 未显示 GalleryDialog。
- 未自动进入 Editor Screen。

## 权限拒绝后重试
Name: 权限拒绝后重试
Entry: LaunchScreen
Steps:
1. 点击 `choose image`。
2. 在读图权限请求中选择“拒绝”。
3. 再次点击 `choose image`。
Expected:
- 第一次拒绝后仍停留在 LaunchScreen。
- 未进入 GalleryDialog 或 Editor Screen。
- 第二次点击后再次触发读图权限请求。

## GalleryDialog 打开并取消
Name: GalleryDialog 打开并取消
Entry: LaunchScreen
Steps:
1. 点击 `choose image` 并完成当前分支所需的读图权限授权。
2. 等待 GalleryDialog 打开。
3. 不选择任何图片，直接关闭弹窗或执行返回操作。
Expected:
- GalleryDialog 成功显示图片列表或可见加载态。
- 关闭后返回 LaunchScreen。
- GalleryDialog 从界面消失。
- 未进入 Editor Screen，且没有选中图片残留。

## 系统图片选择器选 1 张图
Name: 系统图片选择器选 1 张图
Entry: GalleryDialog
Steps:
1. 从 LaunchScreen 进入 GalleryDialog。
2. 点击系统图片选择器入口。
3. 在系统图片选择器中选择 1 张图片并确认。
Expected:
- 系统图片选择器关闭。
- 进入 Editor Screen。
- 主预览显示刚选择的图片。
- 底部预览列表仅显示 1 张图片，且该图片处于选中状态。

## 系统图片选择器选多张图
Name: 系统图片选择器选多张图
Entry: GalleryDialog
Steps:
1. 从 LaunchScreen 进入 GalleryDialog。
2. 点击系统图片选择器入口。
3. 在系统图片选择器中选择多张图片并确认。
Expected:
- 系统图片选择器关闭。
- 进入 Editor Screen。
- 主预览默认显示底部列表中的第一张图片。
- 底部预览列表显示多张图片，且存在一个默认选中项。

## Editor 内切换预览图
Name: Editor 内切换预览图
Entry: Editor Screen（已载入 2 张及以上图片）
Steps:
1. 通过多图选择流程进入 Editor Screen。
2. 点击底部预览列表中当前未选中的另一张图片。
Expected:
- 主预览切换为刚点击的图片。
- 底部列表中被点击的图片变为当前选中项。
- 被选中的图片滚动到可见区域中部或接近中部的位置。

## Editor 返回行为
Name: Editor 返回行为
Entry: Editor Screen
Steps:
1. 从 LaunchScreen 进入 Editor Screen。
2. 点击左上角返回按钮。
Expected:
- 当前 Editor Screen 被关闭。
- 返回 LaunchScreen。
- 返回后未残留 GalleryDialog。

## 保存/导出/分享
Name: 保存/导出/分享
Entry: Editor Screen
Steps:
1. 点击右上角保存/导出入口。
2. 等待保存/导出面板出现。
3. 选择导出参数并执行导出。
4. 在导出完成后尝试分享结果并打开图库预览。
Expected:
- 保存/导出面板成功显示。
- 导出进行中时界面出现明确的进行中状态。
- 导出完成后 `Share` 操作可点击。
- 可以触发系统分享，并可以打开图库预览导出结果。

## Not Covered Yet

- `ACTION_SEND image/*` 外部分享进入 Compose 流程
- 不同 Android 版本下的权限分支差异
- 导出失败、OOM、文件不存在等异常路径
- Recovery mode / crash flow
- 旋转、进程重建、后台恢复等生命周期场景
