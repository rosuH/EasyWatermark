# 字体选择与预览手势：官方交互依据

核验日期：2026-10-17。对应决策：[竞品如何区分照片查看、水印调整与字体选择？](https://github.com/rosuH/EasyWatermark/issues/447)。只使用官方文档；没有安装竞品实测，也不据此宣称首创或更优。

## 已确认的产品行为

| 产品 | 官方行为 | 对本项目的参考边界 |
|---|---|---|
| Photoshop on iPad | 在变换边界框内双指缩放、旋转选中内容；框外双指移动、缩放画布。可拖动边界框中心移动内容。 | 证明同一区域可通过命中目标区分对象与视图；不证明小水印命中容易，也未说明多指途中跨边界的目标锁定规则。 |
| Procreate | 先进入 Transform；框内双指缩放内容，框外双指调整画布缩放和旋转。拖动框内或框外均移动内容，点击框外用于微移；退出 Transform 提交。 | 不能把它描述成“点空白必然取消选择”，也不能直接复刻其单指拖动规则。 |
| Adobe Express mobile | 选中文本后进入 Font；可搜索已有字体，在 Your fonts 上传设备字体，最后勾选应用。 | 支持就地搜索、导入与选择的入口组织；不提供 EasyWatermark 的离线存储或导出一致性证明。 |

来源：[Photoshop iPad Transform](https://helpx.adobe.com/photoshop/ipad/transform-and-add-objects/transforming-objects-ipad.html)、[Procreate Transform gestures](https://help.procreate.com/procreate/handbook/transform/transform-interface-gestures)、[Adobe Express mobile fonts](https://helpx.adobe.com/express/mobile/add-and-customize-text/add-custom-fonts.html)。

## EasyWatermark 的设计推论（尚待用户体验确认）

用户已要求只制作“点选水印”这一版 demo；最终产品交互尚未批准。

- 点中文字显示选择框；从空白开始操作照片，从选中水印开始操作水印。一次手势从首个触点开始锁定目标，第二指加入或手指跨过边界时不切换目标。这是本项目的设计推论，不是竞品文档确认的规则。
- 照片查看变换仅影响视图；水印变换影响编辑配置和最终导出。重复水印任一单元都可选择，但修改同一套全局参数，不为每个平铺副本建立独立对象。
- 点空白取消选择、分开的“重置视图/重置水印”按钮用于降低误操作。原型保留键盘和按钮操作，避免只靠多指手势。

实质差别是离线水印场景的目标锁定与全组编辑语义，不是新增一个通用变换工具。可执行路径是先验证单一 demo，再沿现有 Session 与预览坐标映射实现；避免引入独立编辑状态系统。

验收重点：先点水印再加第二指不会缩放照片；空白处开始不会改变水印；缩放照片后仍能准确命中水印；单个与重复模式语义清楚；重置视图不改导出；选择框不进入导出。用户确认后才确定具体实现和三端设备验收。

风险边界：小字与密集平铺可能让空白区域不足，旋转后命中需坐标逆变换，多指移交易突跳。当前文档研究与原型不能证明这些风险已经解决；原型 review 是下一道决策，不在此自动接受或实施。

字体方面，现有方案保持本地导入、稳定引用、私有副本和预览/导出共同解析；以真实导出和缺失字体行为验收，不能用竞品文档替代运行证据。当前字体 PR 的验收与合入仍独立等待 Human Confirm。
