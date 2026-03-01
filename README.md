# 九号出行 LSPosed 插件

LSPosed/Xposed 模块，为九号出行（cn.ninebot.ninebot）提供主题、车型展示、抓包与投屏等增强功能。

## 功能概览

| 功能 | 说明 |
|------|------|
| **1. 强制开启主题** | 云控未开放时仍显示主题 Tab、主题入口（themeShow=1、内存配置、入口） |
| **2. 破解主题费用** | 已拥有/我的主题库/付费主题、APP 皮肤应用（72057 等）、device/resource 注入 |
| **3. 其他** | 抓包（URL + 加密包）、车辆信息明文上报、反调试、水印、Toast |
| **4. 修改车辆型号** | 仪表盘显示时速（km/h）、轨迹详情电摩展示，数据身份保持 116 |
| **5. 投屏导航叠加** | 地图导航时在投屏画面上叠加自定义内容（当前为验证用网格） |

详细实现、Hook 点、配置项与 MCP/JADX 分析结论见：**[技术说明与实现文档](docs/技术说明与实现文档.md)**。

## 安装与使用

1. 用 Android Studio 打开项目，构建 APK（`app/build/outputs/apk/debug/`）。
2. 安装到设备，在 **LSPosed** 中启用模块，**作用域**勾选「九号出行」和**本模块**。
3. 重启九号出行；在模块主界面配置各开关与 Web 日志服务器（可选）。

## 配置说明

- 配置存储：SharedPreferences 文件名 `theme_config`（与 Manifest 中 xposedsharedprefs 一致）。
- 车辆型号默认 116（九号电动Dz110P）；可先开启「其他」并配置服务器，在日志中查看「车辆信息」确认本车 `vehicle_type` 再填。
- 修改车辆型号为「展示用」：定位/轨迹列表/上送仍为 116；仅仪表盘与轨迹详情页按电摩展示。

## 编译

- 依赖：`libs/xposed-api-82.jar`（compileOnly），见 [Xposed API](https://github.com/rovo89/XposedBridge/wiki)。
- 构建：`./gradlew assembleDebug` 或 IDE Build → Build APK(s)。

## 文档与分析

- **[技术说明与实现文档](docs/技术说明与实现文档.md)**：各功能实现、Hook 点、配置、MCP 工作原理（必读）。
- 仓库内其他 `.md`：投屏流程、电自/电摩逻辑、主题相关分析等，可作为补充参考。

## 版本

- 插件逻辑版本：v49（`HOOK_LOG_VERSION`）。
- 构建版本：见 `build.gradle` 中 `versionCode` / `versionName`。
