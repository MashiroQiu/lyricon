# TTXXXY fork / API 102 release

本版本以 TTXXXY/lyricon 的 ecd34fcec2a8c7d8233b585a5100d886803d631c 为基础，合并 tomakino/lyricon master 的 c93da0eef8b7aee015d2670b48e9e262fcf72cd1。真实 merge 保留双方历史；分叉点是 c47d0fa005aef2e1b9e194e682631d372c3307be。

保留 fork 的 MiuiIslandHooker 目标类、onIslandSizeChanged 回调、横屏退出、时钟切换和岛收起后延迟更新位置。将这些行为移植至上游的新歌词渲染、logo、AI 和手势实现。

## 几何和默认设置

- 小米/Redmi/POCO 竖屏无岛时，从歌词真实屏幕位置到 displayCutout 摄像头左边缘计算宽度，扣除右边距、4dp 间隔和额外 3 个物理像素。无法获得 cutout 时保守回退到屏幕中心减 16dp 再减 3px。
- 有岛时沿用 fork 的实际 Rect 和父容器屏幕坐标算法，宽度增加 2 个物理像素；文本始终在剩余宽度内布局。
- clock 和 notification_icon_area 默认在播放且歌词可见时隐藏。旧设置中的空规则首次升级采用此默认值，已有非空自定义规则保留。
- 通过 libxposed service 首次连接，将旧私有 baseLyricStyle / packageStyleManager / package_style_* 迁移至上游的 lyricon_style_* 共享偏好组。旧隐藏模式 3 转换为上游的隐藏模式 1，保留已配置的播放器和样式。目标组已有设置优先，成功后写入一次性迁移标记。
- 保留点击歌词切换时钟，作为新版手势的默认单击动作；设置页可改为上游控制面板或其他动作。

## 翻译

新渲染器 LyricLineAssembler 优先选择 translation/translationWords，再回退 secondary，最后依据开关选择 roma。SuperLyric 的网易云 secondary 罗马音不再抢占真正的翻译。

如果输入源在没有翻译时将罗马音误标记为 translation，Lyricon 无法从字符串本身可靠识别；这仍需提供端正确标记字段。

## LSPosed API 102

采用 io.github.libxposed:api:102.0.0。minApiVersion/targetApiVersion 均为 102，以 onPackageReady 的最终 classLoader 安装 hook，使用 hook(Method).intercept/Chain.proceed。不包含 legacy xposed_init 或 xposedsharedprefs 元数据。

API 文档：https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar

应用使用上游的 libxposed service 偏好接口；不依赖可被其他应用读取的私有 XML。最终 application 模块 compileOnly API，使 R8 正确将宿主 API 识别为库类型。

## 构建与验证

版本：54 / 1.0.40.rc-xy21-api102；上游最低 Android 10（API 29）。assembleRelease 使用代码和资源缩减，不可调试。保留用户已验证测试 APK 的签名，使其能覆盖更新；它不是 TTXXXY 或 tomakino 的官方签名。不要上传签名私钥。

检查翻译选择单元测试、CameraWidth 的 5 个固定用例和 10000 个边界用例、release 签名与元数据。没有连接小米设备，合并后的 SystemUI 运行行为仍需真机验证。

安装后打开一次应用以迁移设置，确认 LSPosed 作用域为 SystemUI，再重启。重点验证：有岛/无岛长歌词边界、岛收起后恢复、歌词和时钟切换、横竖屏、网易云原文和翻译、设置保存与重新启动。

旧 fork 中原先未引用的源码保留在 archive/unreferenced-upstream；新版实际运行对应的上游实现。
