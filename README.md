# 九号LSPosed插件

LSPosed 模块，为九号出行提供增强功能（如绕过调试器检测等）。

## 功能

1. **绕过「检测到调试器」**：Hook `Debug.isDebuggerConnected()` 始终返回 `false`，避免 APP 因检测到调试环境退出。
2. **尝试强制电摩/显示时速**：对可能存在的「车型/是否电摩」相关类和方法做 Hook，强制走电摩分支（类名根据常见命名猜测，若无效需用 jadx 查到真实类名后改 `NinebotHook.java`）。

## 编译与安装

1. 用 **Android Studio** 打开本目录（`NinebotHook`）。
2. 若提示缺少 Xposed API：在项目根目录放 `libs/xposed-api-82.jar`（从 [Xposed API](https://github.com/rovo89/XposedBridge/wiki) 或 LSPosed 文档获取），并在 `build.gradle` 里改为 `compileOnly files('libs/xposed-api-82.jar')`。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**，在 `app/build/outputs/apk/debug/` 得到 APK。
4. 安装到手机，在 **LSPosed** 里勾选「九号出行」并勾选本模块，重启九号出行。

## 使用

- 安装并启用模块后，打开九号出行应不再提示「检测到调试器」。
- 若骑行详情仍只显示耗电量、不显示时速，说明车型判断不在当前猜测的类里，需在 jadx 里搜到真实类名/方法名后，在 `NinebotHook.java` 的 `possibleClasses`、`methodsReturnBool`、`methodsReturnString` 中补充或修改。

## 修改 Hook 目标

打开 `src/main/java/com/ninebot/hook/NinebotHook.java`：

- `possibleClasses`：可能包含「车型/是否电摩」的类名（从 jadx 反编译结果里找）。
- `methodsReturnBool`：返回 boolean 的方法（如 `isEbike()`），Hook 后统一返回 `false`（表示非电动自行车）。
- `methodsReturnString`：返回类型字符串的方法，Hook 后统一返回 `"emoped"`（可按 jadx 里电摩实际枚举值改）。

保存后重新编译安装即可。
