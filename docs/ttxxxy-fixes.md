# TTXXXY fork 修复说明

基线：TTXXXY/lyricon master，ecd34fcec2a8c7d8233b585a5100d886803d631c。
包名与原 fork 相同，保留 MiuiIslandHooker 的原目标类、onIslandSizeChanged 回调、横屏处理、时钟切换和岛避让算法。

## 宽度

小米/Redmi/POCO 竖屏且无岛时，不再使用固定配置宽度或 WRAP_CONTENT。
从歌词当前屏幕坐标到 WindowInsets.displayCutout 中摄像头的左边界计算可用像素宽度，扣除右边距和 4dp 间隔；文本使用 weight 填满剩余容器。
布局变化时重新计算，因此时钟和锚点位置变化后仍按真实空间限宽。
无法取得 cutout 时保守退回屏幕中心减 16dp 的位置；此回退不等于真机 cutout 数据。
ColorOS 和非小米设备保留原配置行为。

## 翻译

两个歌词渲染路径都优先选择 translation/translationWords，再回退 secondary，最后依据罗马音开关选择 roma。
这修复 SuperLyric 的 secondary（网易云罗马音）遮盖真正翻译的问题。
输入源必须正确标记字段：当前 SuperLyric 的网易云解析器在完全没有翻译时可能把 roma 填入 translation；Lyricon 无法可靠从文字本身判断这种错误，不能凭脚本语言把英语翻译一律丢弃。

## LSPosed API 102

依据 Maven Central 发布的 io.github.libxposed:api:102.0.0 源码 Javadoc：
https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar

- 使用 XposedModule/onModuleLoaded/onPackageReady。onPackageReady 提供 AppComponentFactory 确定的最终 classLoader。
- 使用 hook(Method).intercept 和 Chain.proceed(args)，保留原回调行为；方法异常原样传播，观察回调失败不能影响 SystemUI。
- 删除 legacy assets/xposed_init、旧入口类和 xposedsharedprefs 清单标记。
- META-INF/xposed/module.prop 的 min/target 都为 102。
- 用 getRemotePreferences 读设置；应用通过 libxposed service 101.0.0（独立版本的服务库）写共享副本，不再依赖 world-readable XML。
- 保留本地私有设置、备份和原文件名；首次启动应用迁移并同步。设置变更继续实时同步。

## 真机验证

测试签名与原 fork 正式签名不同，签名冲突时先备份配置再卸载；安装后恢复配置、打开应用让设置同步，LSPosed 勾选 SystemUI，重启。
测试：无岛长歌词到摄像头左边界、有岛限宽、岛消失恢复、时钟切换、横竖屏、网易云同时含原文/翻译/罗马音歌曲，以及设置修改后的实时同步。
本环境没有连接小米设备，编译和静态/单元验证不能替代真机确认。

## 干净检出构建一致性

当前 master 混合了旧的运行实现与未被引用的新实现。编译时修正 ViewVisibilityPolicy 的旧视图 API 名称、SubscriberRemoteEndpoint 的命名参数；将未被运行路径引用且缺失依赖的新 logo、背景和 AI 翻译实现原样归档到 archive/unreferenced-upstream。实际使用的 SuperLogo、AITranslator 和设置页仍保留。
移除 JDK 21 不支持的 CMSClassUnloadingEnabled 构建参数。测试 APK 使用标准 debug 签名。
