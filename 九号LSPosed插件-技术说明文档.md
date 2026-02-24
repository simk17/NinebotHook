# 九号 LSPosed 插件 (NinebotHook) — 技术说明文档

本文档基于当前仓库代码整理，用于维护与对外说明。版本对应：**插件逻辑 v42 / 构建 versionName 0.42**。

---

## 1. 关键信息

| 项目 | 说明 |
|------|------|
| **目标应用** | 九号出行 `cn.ninebot.ninebot` |
| **插件包名** | `com.ninebot.hook` |
| **Xposed API** | 82，LSPosed 最低版本 93 |
| **构建版本** | `build.gradle`: versionCode 42, versionName "0.42" |
| **日志版本** | `HOOK_LOG_VERSION = 42`（日志中显示「插件v42」） |

---

## 2. 安装与激活

在 LSPosed 管理器中需完成：

1. **激活模块**：打开本模块开关。
2. **设置作用域**（必做）：
   - 勾选 **「九号出行」**（目标应用）；
   - 勾选 **「九号LSPosed插件」/ 本模块自身**。  
   勾选模块自身是为了让九号进程能通过 **XSharedPreferences** 读取模块目录下的 `theme_config` 配置（与 `AndroidManifest.xml` 中 `xposedsharedprefs` 一致）。
3. **重启九号出行** 使作用域与配置生效。

---

## 3. 配置项说明

配置通过 **SharedPreferences 文件名 `theme_config`** 存储，与 Manifest 中 `xposedsharedprefs` 一致。  
- **写入**：在**模块进程**内由 MainActivity / HookConfig 写入（`MODE_WORLD_READABLE`，便于被目标读取）。  
- **读取**：在**九号进程**内由 NinebotHook 通过 **XSharedPreferences(MODULE_PACKAGE, "theme_config")** 读取。

| 配置项 | Key | 说明 |
|--------|-----|------|
| **启用主题破解** | `enable_theme_hack` | 为 true 时执行解密/内存/UI/入口的篡改与注入；为 false 时 Hook 仍挂载但不改数据、不强制入口。默认 true。 |
| **Web 日志服务器** | `server_url` | ReportHelper 上报的 base URL，如 `192.168.1.5:8765`。九号进程内在 handleLoadPackage 时通过 XSharedPreferences 读并调用 `ReportHelper.setCustomServerUrl(serverUrl)`。 |

MainActivity 界面行为：

- **主题开关**：对应 `enable_theme_hack`，保存到 HookConfig（theme_config）。
- **服务器 IP 输入框 +「保存设置」**：对应 `server_url`，保存到 HookConfig；保存后下次 Hook 生效时会用新地址上报。

---

## 4. 技术实现原理（五层 Hook）

插件在「解密 → 入口判断 → 内存配置 → UI 列表」整条链路上做注入，并在类未加载时通过 **ClassLoader.loadClass** 监听做延迟安装。

### 4.1 整体流程

- **installHooks(loader)**  
  - 先 **tryHookAll(loader)** 尝试一次性注入；  
  - 再 **hookAllMethods(ClassLoader.class, "loadClass", ...)**，在任意 `name.contains("ninebot")` 的类被加载时再次调用 **tryHookAll(loader)**，用于延迟安装尚未加载的类。
- **tryHookAll** 内部依次调用（各方法内用 `findClassIfExists` + 标志位，避免重复挂载）：
  - tryHookRetrofit
  - tryHookDecrypt
  - tryHookMemoryConfig
  - tryHookThemeUI
  - tryHookEntry

### 4.2 各层说明

**A. 网络抓包层（RetrofitStrategy）**

- **类**：`cn.ninebot.lib.network.core.RetrofitStrategy`
- **方法**：`customizeOkHttpClient2`
- **动作**：在 OkHttpClient.Builder 上添加拦截器，请求/响应体打日志并通过 ReportHelper 上报（tag「抓包」）。
- **条件**：无主题开关判断，仅受「类是否已加载」与 `hookedRetrofit` 控制。

**B. 数据解密层（NeteaseDecrypt）**

- **类**：`cn.ninebot.library.network.encrypt.netease.NeteaseDecrypt`
- **方法**：`decodeContent`
- **动作**：在 `afterHookedMethod` 中取解密结果字符串，若包含 `themeShow`，则用正则 `"themeShow":\s*[0-9]+` 替换为 `"themeShow":1`，并 `setResult(modified)`。
- **条件**：仅当 `isThemeHackEnabled()` 为 true 时执行替换；否则只拿到解密结果不修改。

**C. 入口层（TopGuideDeviceDetailFragmentKt）**

- **类**：`cn.ninebot.device.topguide.TopGuideDeviceDetailFragmentKt`
- **方法**：`isUseTopGuideMode`
- **动作**：`afterHookedMethod` 中若 `isThemeHackEnabled()` 为 true，则 `setResult(true)`，强制进入带「车控/主题」的详情页。
- **条件**：仅受主题开关控制。

**D. 内存配置层（TopGuideConfigRepository）**

- **类**：`cn.ninebot.device.topguide.TopGuideConfigRepository`
- **方法**：`getTopGuideTabConfig`
- **动作**：对返回值 config 不为 null 时，`XposedHelpers.setIntField(config, "themeShow", 1)` 并 `setResult(config)`，保证内存里读到的是 themeShow=1。
- **条件**：仅当 `isThemeHackEnabled()` 为 true 时执行。

**E. UI 注入层（TopGuideDeviceDetailViewModel）**

- **类**：`cn.ninebot.device.topguide.TopGuideDeviceDetailViewModel`
- **方法**：`updateTabList`
- **动作**：在 `beforeHookedMethod` 中取 `param.args[0]`（List），若未包含 `TopGuideTab.Theme`，则将该静态常量插入列表，从而强制显示主题 Tab。
- **条件**：仅当 `isThemeHackEnabled()` 为 true 时执行。

### 4.3 配置读取（九号进程内）

- **主题开关**：`NinebotHook.isThemeHackEnabled()` → `XSharedPreferences(MODULE_PACKAGE, "theme_config").getBoolean("enable_theme_hack", true)`，并 `reload()`。
- **服务器地址**：`getRemoteServerUrl()` → 同上 prefs 的 `getString("server_url", "")`；非空时在 handleLoadPackage 中调用 `ReportHelper.setCustomServerUrl(serverUrl)`。

---

## 5. 辅助功能

- **反调试**：Hook `Debug.isDebuggerConnected()` 恒返回 false。
- **Toast**：在目标 Application.attach 后约 2 秒弹出「九号LSPosed注入成功 v41」。
- **水印**：在所有九号 Activity 的 onResume 时，在根 View 上添加半透明 “Hook v41” 文本，tag `ninebot_hook_watermark` 防重复。

---

## 6. 与配置/日志相关的类

- **HookConfig**：模块进程内读写 `theme_config`（ServerUrl、ThemeHackEnabled），使用 `MODE_WORLD_READABLE` 的 SharedPreferences，文件名与 Manifest 中 xposedsharedprefs 一致。
- **ReportHelper**：上报到 Web；优先使用 `setCustomServerUrl` 注入的地址，否则通过 `HookConfig.getServerUrl(sContext)` 读（依赖模块内保存的 server_url）；再否则用默认 `DEFAULT_BASE_URL`。
- **ConfigProvider**：当前为空壳，未参与主题或服务器配置的读写。
- **MainActivity**：提供主题开关、服务器 IP 输入框、保存按钮，以及停止/重启/启动九号的快捷操作（依赖 su 的 force-stop）。

---

## 7. Web 日志服务（server.py）

- 项目内提供 `server.py`，在电脑上运行后监听 8765 端口；插件通过 HTTP GET `/report?tag=...&msg=...` 上报，浏览器访问 `http://本机IP:8765` 可查看日志。
- 若浏览器空白，请确认未走代理、访问的为 `http://127.0.0.1:8765`，且本机防火墙/代理未将 8765 转到其他端口。

---

## 8. 常见问题排查

- **Web 日志显示「未设置/无法读取」**  
  - 检查 LSPosed 作用域是否勾选**本模块**；  
  - 在插件 MainActivity 中保存一次「Web 日志服务器」并确认保存成功；  
  - 重启九号出行后再看上报。

- **日志里出现 ClassNotFoundException**  
  - 当前实现已通过 **ClassLoader.loadClass** 监听在任意包含 "ninebot" 的类加载时再次尝试 tryHookAll，多数延迟加载类会在进入相关页面后被挂上。若仍缺失，可多切换几次设备详情/车控/主题相关页面触发类加载。

- **配置修改后不生效**  
  - 主题开关与 server_url 在九号进程内每次使用前会通过 XSharedPreferences.reload() 读取；若仍怀疑缓存，可点击插件内「重启九号」再试。

- **主题仍不显示**  
  - 确认「启用主题破解」为开；  
  - 确认进入的是「设备列表 → 点进某台车」后的详情页（车控/主题 Tab 仅在此页）；  
  - 查看 Web 日志是否出现「注入成功」相关条目（解密层、内存层、UI 层、入口层）。

---

## 9. 版本与维护说明

- **v36–v40**：基础 Hook、日志、配置同步与延迟加载处理。
- **v41（当前逻辑）**：  
  - 配置文件统一为 `theme_config`（与 Manifest xposedsharedprefs 一致）；  
  - 九号进程内通过 XSharedPreferences 读 enable_theme_hack / server_url，并调用 ReportHelper.setCustomServerUrl；  
  - 五层 Hook（抓包 + 解密 + 入口 + 内存 + UI）全部接好，内存层恢复 themeShow 篡改；  
  - 通过 loadClass 监听在类延迟加载时再次 tryHookAll，减少 ClassNotFoundException。

若后续九号 APP 混淆或包名/类名变更，可结合 Debug 构建并 **Attach Debugger to Process（附加到九号进程）**，在 `decodeContent` 的 afterHookedMethod 处断点查看解密后 JSON 结构，再据此调整正则或字段名。

---

*文档与当前代码库一致，最后更新以仓库为准。*
