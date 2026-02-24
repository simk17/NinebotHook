# 九号 APP 主题 Tab — 官方逻辑与 Hook 点

（以下依据 JADX 反编译九号官方代码整理。）

**结论：官方完全按「云控配置」决定是否显示主题 Tab。我们只改云控下发的配置数据（themeShow=1），即可直接启用功能，无需改其它逻辑。**

**重要：「车控 | 主题 | 会员」这三个 Tab 只出现在「设备详情页」——即从 设备列表 点进 某一台车 后的那一页；首页、皮肤设置页、设备列表页本身都不会有这几个 Tab。请务必：设备列表 → 点击某台车 → 进入详情页，在详情页顶部才能看到 车控|主题|会员。**

---

## 一、官方何时、依据什么“显示”主题 Tab

1. **入口判断**（是否进入带 Tab 的详情页）  
   - `isUseTopGuideMode(deviceBean)` 为 true 时，才进入 TopGuide 设备详情（有车信号、蓝牙图标、Tab 栏的那一页）。  
   - 内部会调 `TopGuideConfigRepository.getTopGuideTabConfig(wnumber)`，用返回的 `TopGuideTabConfig` 做 `isHasTopGuide()`（即 themeShow==1 或 vipShow==1）。  
   - **结论**：若希望进入该详情页，必须让 **getTopGuideTabConfig(wnumber)** 返回的配置里至少 themeShow 或 vipShow 为 1。

2. **详情页里“组 Tab 列表”并下发显示**  
   - 进入详情页后，`TopGuideDeviceDetailViewModel.fetchTabConfig(deviceBean)` → 协程里调 **queryDeviceTopGuideConfig(uid, deviceBean)**（suspend）。  
   - 拿到的 **TopGuideTabConfig** 决定 Tab 列表：  
     - 先加「车控」；  
     - 若 `config.isHasTheme()`（即 themeShow==1）则加「主题」；  
     - 若 `config.isHasMember()`（即 vipShow==1）则加「会员」。  
   - 然后调用 **updateTabList(list)**，Fragment 观察该 list 后执行 **setDataList(list) + bindTabsWithMediator(list)**，UI 上才画出「车控 | 主题 | 会员」等 Tab。  
   - **结论**：官方“显示”主题 Tab 的时机是：**拿到 config 后根据 config 组好 list，再通过 updateTabList(list) 把 list 交给 UI**。数据来源就是 **queryDeviceTopGuideConfig 的返回值**（通过 Continuation 回调给 ViewModel）。

3. **配置从哪里来（云控）**  
   - Repository 内：`queryDeviceTopGuideConfig` 先 `ensureMemoryCacheLoaded()`，再从 **memoryCache.get(wnumber)** 取 `TopGuideTabConfig` 并返回（suspend 通过 Continuation.resume(result) 把该 config 交给调用方）。  
   - 缓存来自：本地加密文件 `getFilesDir()/device_top_guide_cache.json` 解密，或服务端接口拉取后写入 memoryCache。  
   - **TopGuideTabConfig** 即云控下发的配置：`(wnumber, themeShow, vipShow)`；`isHasTheme()` = (themeShow==1)，`isHasMember()` = (vipShow==1)。

**官方代码依据（JADX）：**
- `TopGuideTabConfig`：`isHasTheme()` 为 `themeShow == 1`，`isHasMember()` 为 `vipShow == 1`。
- `TopGuideConfigRepository.getTopGuideTabConfig(sn)`：从 `memoryCache.get(sn)` 取配置。
- `TopGuideConfigRepository.queryDeviceTopGuideConfig(uid, deviceBean, Continuation)`：先 `ensureMemoryCacheLoaded()`，再从 `memoryCache.get(wnumber)` 取 config 通过 Continuation 返回。
- `TopGuideDeviceDetailViewModel.fetchTopGuideConfig`：调 `queryDeviceTopGuideConfig` 拿到 config 后，`ArrayList` 先加 Vehicle，**若 config.isHasTheme() 则加 Theme**，若 config.isHasMember() 则加 Member，最后 `updateTabList(list)`。

---

## 二、官方“是否进入 TopGuide 详情”的完整条件（易漏点）

**isUseTopGuideMode(DeviceBean)** 为 true 时才会进入有「车控|主题」Tab 的详情页。该函数逻辑（JADX）：

1. **设备类型**：`DynamicConfigManager.getDeviceConfigWithServerId(vehicleType).getModelIgnoreCase()` 必须为 **MOTOR** 或 **ASSISTED_EBIKE**，否则直接 false。
2. **配置**：`getTopGuideTabConfig(wnumber)` 非 null 且 **isHasTopGuide()**（即 themeShow==1 或 vipShow==1）。
3. **地区**：`!NBAppConfig.isOverseas()`。

因此：若设备类型不是 MOTOR/ASSISTED_EBIKE，仅改 getTopGuideTabConfig 仍不会进详情页。插件在「强制主题」时同时 **Hook isUseTopGuideMode 强制返回 true**，保证能进入详情页。

---

## 二点五、皮肤设置里「看不到任何皮肤」的排查

- 关插件时皮肤设置里有默认皮肤，开插件后为空，说明**插件可能影响了展示主题/皮肤列表的那条链路**，或强制进入 TopGuide 后走了与「默认皮肤」不同的分支。
- 当前 Hook 只改：**getTopGuideTabConfig 返回值、queryDeviceTopGuideConfig 的 Result、getTopGuideConfig 响应、updateTabList 的 list、isUseTopGuideMode 返回值**。没有直接改任何「皮肤列表」接口。
- 建议在 JADX 里搜：**主题子 Tab / 皮肤列表** 的加载处（例如某 Fragment、Repository 或 API 返回的 list），确认是否与 TopGuide 缓存/云控共用同一数据源；若共用，需在改 themeShow 时避免把该 list 清空或误替换。

---

## 三、可选的“改官方指令/数据包”的 Hook 点

| 层级 | 含义 | Hook 点 | 效果 |
|------|------|---------|------|
| **配置读取** | 改“读到的配置” | **getTopGuideTabConfig(wnumber)** 的返回值 | 入口页认为有 TopGuide，会进详情页；但详情页里组 Tab 仍用 queryDeviceTopGuideConfig 的结果。 |
| **配置下发** | 改“给 ViewModel 的配置” | **queryDeviceTopGuideConfig** 通过 Continuation 传回的结果 | ViewModel 拿到的就是 themeShow=1，会按官方逻辑自己加「主题」并 updateTabList，无需再改 list。 |
| **显示列表** | 改“要显示的 Tab 列表” | **updateTabList(list)** 的入参 list | 不关心 config，直接在最终 list 里插入 Theme，保证界面有「主题」Tab。 |

推荐：**只改云控配置数据**（getTopGuideTabConfig 返回值 + queryDeviceTopGuideConfig 的 Continuation 里传回的 config 改为 themeShow=1），不改业务代码；官方逻辑不变，只是“下发的配置”里 themeShow=1。若 Continuation 难 Hook，再保留 **updateTabList** 在 list 里强制插入 Theme 作为保底。

---

## 四、当前插件策略（与本文对应）

- **getTopGuideTabConfig**：返回值改为 themeShow=1（或无配置时伪造一份），保证能进 TopGuide 详情页。  
- **queryDeviceTopGuideConfig**：suspend 结果通过 Continuation 传回；若能在 Continuation.resumeWith 处把 Result 里的 config 改成 themeShow=1，则 ViewModel 会按官方逻辑加主题 Tab；否则用 updateTabList 在 list 里强制插入 Theme 作为保底。

---

## 五、排查「没有车控/主题菜单」时

若配置已为 强制主题=true，但界面仍无「车控」「主题」Tab：

1. **看 Web 日志**：进入你认为有「车控」的页面后，是否出现 **进度** 类型下的：
   - `getTopGuideTabConfig 被调用`
   - `queryDeviceTopGuideConfig 被调用`
   - `updateTabList 被调用`
2. **若从未出现**：说明当前看到的首页/车控界面**不是** TopGuide 详情页（可能是 React Native 或别的 Activity），需要反编译九号 APP，搜索「车控」「主题」或 TopGuide 相关类，找到真正的 Tab 构造入口再 Hook。
3. **若出现了**：说明 TopGuide 已走通，再根据日志看是否「已改包」「已注入 Theme Tab」及是否有异常。

---

## 六、云控请求地址与缓存（JADX 确认）

- **接口（主题/车控入口配置）**  
  - 类：`cn.ninebot.device.network.TopGuideApiService`  
  - 方法：`getTopGuideConfig(String snParams, String wnumber, Continuation)`  
  - **URL**：`POST /app-api/theme/v1/batch-show-config`  
  - 参数：`snParams`（或 `snParams[]` 批量）、`wnumber`（KEY_WNUMBER）  
  - 返回：`CommonServerResponse<EntranceResult>`，`EntranceResult.getShowEntranceList()` 为 `List<TopGuideTabConfig>`（每项含 wnumber, themeShow, vipShow）。

- **其它主题相关接口**  
  - 主题版本：`POST /app-api/theme/v1/get-new-version`  
  - 红点清除：`POST /app-api/theme/v1/cancel-reddot`

- **本地缓存**  
  - 文件：`getFilesDir()/device_top_guide_cache.json`（应用私有目录）  
  - 内容：AES 加密的 `List<TopGuideTabConfig>` JSON；解密后写入 `TopGuideConfigRepository.memoryCache`。  
  - `getTopGuideTabConfig(sn)`、`queryDeviceTopGuideConfig` 均从 memoryCache 取或先拉网络再写入 cache 后取。

插件若需确认「是否拦截到请求/返回」，可在 logcat 或 Web 日志中搜索：**【拦截】**、**【改值】**、**【拦截返回】**。

---

## 七、抓包与加密响应 / 是否要代理（你抓到的 r/s/v）

你抓到的 **batch-show-config** 响应是**加密体**，不是明文 JSON：

```json
{"r":"BYNzqSpKPFFOcGeTfbhcD0...","s":"F8t4cNsHdX1YtYBwXV1s29...","v":101}
```

- **r**：密文 payload（网易易盾/NetSecKit 加密），解密后才是 `{ "code", "data", "tip" }` 这种标准结构，其中 `data` 里才是 `EntranceResult`（showEntranceList → List&lt;TopGuideTabConfig&gt;）。
- **s**：与校验/签名相关。
- **v**：版本号（如 101）。

**JADX 结论：**

- 九号用 **CommonHttpClient**（needEncrypt=true）加了一层 **BaseParametersInterceptor**，内置 **NeteaseDecrypt**（`SecruityInfo.decryptStringFromServer(str)`）对响应里的 **r** 做解密。
- 解密后的 JSON 再交给 Gson 反序列化成 `CommonServerResponse<EntranceResult>`，最后才变成 memoryCache 里的 `TopGuideTabConfig`。

**对插件的含义：**

- **是否要「转发请求到我们自己的代理、改明文再发给九号」**：服务端返回的是 r/s/v 加密体，解密依赖 app 内网易易盾（NetSecKit），我们拿不到密钥/SDK，**无法在自建代理上解密出明文**。可行做法只有：在**进程内**解密后的链路上改（即当前 Hook 方式），等价于「拿到明文再改」。
- 不需要抓包，也**不用**在原始 HTTP 体上改 r/s/v（要自己加密、签名，成本高且易出错）。
- 插件在**解密之后**的内存/回调上直接改值即可；且**能拿到具体值**：在 Hook 里拿到的就是解密后的对象（如 `TopGuideTabConfig.getThemeShow()`），日志里会打「实际 themeShow=xxx」。
- **刷新机制**：为避免一刷新配置被覆盖，插件在三条路径上都做了强制改值：
  1. **API 层**：Hook `getTopGuideConfig` 的 Continuation，把返回的 `CommonServerResponse.data`（EntranceResult）里所有 config 改为 themeShow=1 → 写入 memoryCache 时已经是 1。
  2. **读缓存/结果**：Hook `getTopGuideTabConfig` 返回值、`queryDeviceTopGuideConfig` 的 Continuation 结果 → 每次读取（无论来自缓存还是刚拉取）都改为 themeShow=1。
  3. **保底**：Hook `updateTabList`，在最终 Tab list 里强制插入 Theme，不依赖前面 config。

**两个接口区别：**

- **`/app-api/theme/v1/batch-show-config`**：主题/车控入口配置，返回的 data 即 `EntranceResult`（showEntranceList），是我们要改的云控来源。
- **`/app-api/survey/v1/info`**：问卷类接口，与主题 Tab 无关，可忽略。
