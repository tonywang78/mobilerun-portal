# Car GUI Agent

车机 GUI Agent（`:agent` / `com.mobilerun.caragent`）与 Mobilerun Portal（`:app`）双 APK 部署。Agent 对齐 [mobilerun](https://github.com/droidrun/mobilerun) 的 `MobileAgent` 编排模型（Manager / Executor / FastAgent、SharedState、ToolRegistry、App Cards、credentials、轨迹事件），通过本机 Portal HTTP/WS 控制车机 UI。

**执行顺序与分支图**见 [car-agent-execution.md](car-agent-execution.md)。

## 平台要求

- Android Automotive / Android 10+（API 29+）
- Portal `minSdk` 已提升至 29，并声明 `android.hardware.type.automotive`（`required=false`，便于手机联调）

## 安装顺序

1. 安装并打开 **Mobilerun Portal**
2. 开启 Accessibility、Notification Listener、本地 HTTP（默认 `8080`）
3. 安装 **Car GUI Agent**
4. 在 Agent「配置」中填写 Portal URL / token，或点「从 Portal 发现 token」

Token 也可通过：

```bash
adb shell content query --uri content://com.mobilerun.portal/auth_token
```

## 执行模式（首页编排路径）

在首页选择谁跑任务（对应 `CarAgent` 分支）：

| 模式 | 行为 |
| --- | --- |
| 快速 | `FastAgent` 单轮工具循环 |
| 深度推理 | `ManagerAgent` → `ExecutorAgent`（先规划再执行） |
| 决策模型 | `JevAgent` System One observe→decide→execute（不走 Manager/Executor） |

决策模型对齐 [mobile-jev](https://github.com/droidrun/mobile-jev)，但**不**依赖 Mobilerun 云设备 / Node studio。配置页分区与首页执行模式对齐：

- **快速 · 深度推理**：选 LLM（启发式 / OpenAI）
- **决策模型**：选 System One（TypeSafe 云端 / 本地 Laya / 自定义）
- 页底统一「保存」

| 决策服务 | 行为 |
| --- | --- |
| TypeSafe 云端 | `https://api.typesafe.ai` + API key（[console.typesafe.ai](https://console.typesafe.ai/)） |
| 本地 Laya | `http://127.0.0.1:8000`（或车机可达地址）+ 兼容 `/v1/systemone` |
| 自定义 | 任意兼容 endpoint |

官方 Jev **不能**打进 APK；本地需另起兼容服务，例如：

```bash
pip install "laya[serve]"
LAYA_JEV_STRICT=1 laya-serve   # 默认 0.0.0.0:8000
```

模拟器访问宿主机 Laya 时，把 base URL 改成 `http://10.0.2.2:8000`（AAOS/Android 模拟器惯例）。

## LLM 后端（配置页）

仅影响「快速 / 深度推理」路径：

| 后端 | 行为 |
| --- | --- |
| 启发式 | 本地关键词规划（无 key 兜底） |
| OpenAI 兼容 | 真 HTTP `chat/completions`（需 base URL + API key + model） |

## 驾驶分级策略

- **低风险**（通知摘要、日程、教程朗读）：行驶中允许
- **中风险**（打开设置等）：驻车或低速
- **高风险**（网页 GUI / YouTube / 截图检索）：仅驻车

主界面「开发模拟 · 车况」可切换驻车 / 倒车 / 空挡 / 市区 30 / 城快 60 / 高速 100，用于验证门禁。

## 与 mobilerun 概念对照

| mobilerun | Car GUI Agent |
| --- | --- |
| `MobileAgent` | `CarAgent` |
| Manager / Executor | `ManagerAgent` / `ExecutorAgent` |
| FastAgent | `FastAgent` |
| `MobileAgentState` | `CarAgentState`（+ vehicle / risk / mode） |
| ToolRegistry + atomic tools | `ToolRegistry` + `PortalTools` |
| App cards | `assets/app_cards/*/CARD.md` |
| `type_secret` / credentials | `CredentialStore` |
| LLM profiles | `LlmProfileRouter` |
| `send_user_message` | `CarAgent.sendUserMessage` |

## 用例矩阵

| # | 能力 | 默认模式 | 主要工具 |
| --- | --- | --- | --- |
| 1 | CarPlay/原生通知摘要、日程 | Direct | `list_notifications` `summarize_notifications` `query_calendar` |
| 2 | 日式生活站 GUI | Reasoning | `jp_lifestyle_open` + Portal click/state |
| 3 | YouTube 网页 | Reasoning | `yt_open` `yt_skip_ad` `yt_seek` |
| 4 | 商品官网检索截图 | Reasoning | `product_search` `save_screenshot` |
| 5 | 车辆设置与教程 | Direct / Reasoning | `speak_tutorial` `open_settings_guide` |

CarPlay 通知若不可达，用例 1 会降级为原生通知缓冲，并在结果中说明。

## 验收清单

- [ ] Portal API 29 设备：`/ping` `/version` `/state_full` `/screenshot` `/tap`
- [ ] Agent 文本指令「打开设置」可走 Fast/Reasoning 并调用 Portal
- [ ] Mock 行驶中高风险 goal 被 `DrivingPolicyGate` 拒绝
- [ ] 通知/日程/YouTube/商品检索/教程各至少一条驻车路径
- [ ] 语音输入可写入 goal（需麦克风权限）
- [ ] 同时仅一个高风险 GUI 任务执行，其余入队

## 构建

```bash
./gradlew :app:assembleDebug :agent:assembleDebug :agent:test
```
