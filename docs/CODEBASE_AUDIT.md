# OrangeChat 源码与功能现状盘点

## 1. 范围与判定方法

本文记录 OrangeChat 当前稳定基线的静态源码盘点结果，供后续选择性维护和功能改造使用。

- 审计基线：`master` 提交 `6ce4f0e8453fdd99ccfe88ef1d2496d4ab4cbcc4`。
- 审计方式：读取 Gradle、Manifest、Kotlin/Java/C++/TypeScript 源码、资源、Room schema、测试和 CI 配置；未使用私人运行时配置或外部账号。
- `已实现`：存在入口及主要执行路径；不代表已完成真机、账号或全场景验证。
- `部分实现`：存在主要代码，但有明确限制、缺口或只覆盖部分场景。
- `已禁用`：源码或构建配置明确关闭。
- `未确认`：仅靠静态源码不能确认运行效果、服务端行为或数据兼容性。

本文不会记录任何实际 Token、API Key、私人 MCP 地址、用户人格设定、记忆或日记内容。

## 2. 项目结构地图

### 2.1 Gradle/Android 模块

`settings.gradle.kts` 的 `include` 是模块清单；`app/build.gradle.kts` 是最终 Android 应用的聚合点。

| 模块 | 状态 | 职责 | 代码依据 |
| --- | --- | --- | --- |
| `app` | 已实现 | Android 应用、Compose UI、导航、依赖注入、数据层、Chat 编排、MCP、系统工具和后台服务 | `app/build.gradle.kts` 的 `com.android.application` 与各 `project(...)` 依赖；`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` 的 `NavDisplay`；`RikkaHubApp.kt` 的 `appModule`/`dataSourceModule` |
| `ai` | 已实现 | Provider 抽象、模型、消息结构、工具协议和 OpenAI/Google/Claude 实现 | `ai/src/main/java/me/rerere/ai/provider/Provider.kt` 的 `Provider`；`ProviderManager.kt`；`providers/OpenAIProvider.kt`、`GoogleProvider.kt`、`ClaudeProvider.kt` |
| `search` | 已实现 | 联网搜索服务抽象及多种后端适配 | `search/src/main/java/me/rerere/search/SearchService.kt`；同目录 `TavilySearchService`、`SearXNGService`、`ZhipuSearchService` 等 |
| `speech` | 已实现 | TTS 配置、合成、播放及多后端适配 | `speech/src/main/java/me/rerere/tts/provider/TTSProvider.kt`、`TTSManager.kt`；`controller/TtsController.kt`；`provider/providers/*TTSProvider.kt` |
| `common` | 已实现 | 通用 HTTP、SSE、JSON、缓存及 Android 辅助代码 | `common/src/main/java/me/rerere/common/http/Request.kt`、`SSE.kt`；`cache/CacheStore.kt`；`android/ContextUtil.kt` |
| `document` | 已实现 | PDF、DOCX、PPTX、EPUB、CSV 解析和 Excel 生成 | `document/src/main/java/me/rerere/document/PdfParser.kt`、`DocxParser.kt`、`PptxParser.kt`、`EpubParser.kt`、`CsvParser.kt`、`ExcelGenerator.kt` |
| `highlight` | 已实现 | Prism 驱动的代码高亮 | `highlight/src/main/java/me/rerere/highlight/Highlighter.kt`、`HighlightText.kt`；`highlight/src/main/res/raw/prism.js` |
| `web` | 已实现 | Ktor 内嵌服务器和静态 Web UI 资源容器 | `web/src/main/java/me/rerere/rikkahub/web/Entry.kt` 的 `startWebServer`；`web/build.gradle.kts` 的 `buildWebUi` |
| `workspace` | 已实现 | Android 内的工作区文件系统、终端和 PRoot 环境 | `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt`、`WorkspaceShellRunner.kt`、`RootfsInstaller.kt`；`workspace/src/main/cpp/CMakeLists.txt` |
| `material3` | 已实现 | 本地 Material 颜色工具扩展 | `material3/src/main/java/me/rerere/material3/DynamicSchemeExt.kt`；`material3/build.gradle.kts` 的 `sourceSets.main.java.srcDir("material-color-utilities/kotlin")` |

### 2.2 构建链路与子模块

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| Android 应用使用 `applicationId` `me.rerere.orangechat`，最低 API 26，compile/target SDK 37，Java 17 | 已实现 | `app/build.gradle.kts` 的 `defaultConfig`、`compileSdk`、`compileOptions` |
| `assembleDebug` 会先构建 Web UI，因此还需要 Node/pnpm 依赖链 | 已实现 | `web/build.gradle.kts` 的 `preBuild.dependsOn(buildWebUi)`；`buildWebUi` 执行 `pnpm run build`；`web-ui/package.json` 的 `build` |
| Material 颜色工具是当前构建必需的 git submodule | 已实现 | `.gitmodules` 的 `material3/material-color-utilities`；`material3/build.gradle.kts` 将其 `kotlin` 目录加入 main source set |
| MNN native 构建当前没有进入 Gradle 构建链 | 已禁用 | `.gitmodules` 仍声明 `ai/src/main/cpp/mnn`；`ai/build.gradle.kts` 中 `externalNativeBuild`/CMake 配置被注释；当前基线树没有对应 gitlink |
| Workspace native 代码仍参与构建并携带两种 ABI 的 PRoot 动态库 | 已实现 | `workspace/build.gradle.kts` 的 `externalNativeBuild`；`workspace/src/main/cpp/workspace.cpp`、`termux_pty.cpp`；`workspace/src/main/jniLibs/{arm64-v8a,x86_64}` |
| Debug CI 补齐 Material submodule、Node/pnpm、JDK 17、Debug keystore 后执行 Wrapper | 已实现 | `.github/workflows/debug-build.yml` 的 checkout/submodule、pnpm、Node、Java、keystore、`./gradlew assembleDebug` 和 artifact 步骤 |

关键版本由 `gradle/libs.versions.toml` 集中管理。当前包括 AGP 9.1.1、Kotlin 2.3.21、Compose BOM 2026.05.00、Room 2.8.4、Ktor 3.4.3、Koin 4.2.1 和 MCP Kotlin SDK 0.14.0。该文件还包含 alpha、snapshot 和 JitPack 来源的组件，见风险章节。

## 3. 应用入口、页面与导航

### 3.1 启动路径

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| Application 入口是 `RikkaHubApp`，负责 Koin、WorkManager、通知频道、QuickJS、文件同步和后台能力恢复 | 已实现 | `app/src/main/AndroidManifest.xml` 的 `android:name=".RikkaHubApp"`；`RikkaHubApp.kt` 的 `onCreate`、`startKoin`、`getWorkManagerConfiguration` |
| Launcher Activity 是 `RouteActivity`，默认路由为聊天页 | 已实现 | Manifest 中 `.RouteActivity` 的 MAIN/LAUNCHER；`RouteActivity.kt` 的 `rememberNavBackStack(Screen.Chat)` |
| 分享文本和 `PROCESS_TEXT` 会进入同一个 Activity 的分享处理路径 | 已实现 | Manifest 的 SEND/PROCESS_TEXT intent filter；`RouteActivity.kt` 的 `handleIntent` 和 `Screen.ShareHandler` |
| 快捷方式、MCP OAuth、Safe Mode、应用锁和生物识别有独立 Activity | 已实现 | Manifest；`ShortcutHandlerActivity.kt`、`McpOAuthCallbackActivity.kt`、`SafeModeActivity.kt`、`AppLockUnlockActivity.kt`、`BiometricPromptActivity.kt` |

### 3.2 主要页面

`RouteActivity.kt` 的 `sealed interface Screen : NavKey` 和 `entryProvider` 是导航事实来源。当前主要页面族如下。

| 页面族 | 状态 | 代码依据 |
| --- | --- | --- |
| 聊天、历史、收藏、搜索、分享处理 | 已实现 | `Screen.Chat`、`History`、`Favorite`、`MessageSearch`、`ShareHandler`；`ui/pages/chat`、`history`、`favorite`、`search`、`share` |
| Assistant 及提示词、记忆、请求参数、MCP、本地工具、扩展设置 | 已实现 | `Screen.Assistant*` 路由；`ui/pages/assistant/detail` 的 `AssistantBasicPage`、`AssistantMemoryPage`、`AssistantMcpPage`、`AssistantLocalToolPage` |
| Provider、模型、联网搜索、语音、MCP、文件、Web Server、显示、安全和开发设置 | 已实现 | `Screen.Setting*` 路由；`ui/pages/setting` 及子目录 |
| 图片生成、翻译、内置 WebView、语音通话、健康和统计 | 已实现 | `Screen.ImageGen`、`Translator`、`WebView`、`VoiceCall`、`Health`、`Stats`；对应 `ui/pages/*` |
| Workspaces/终端、工作流、插件、Skills、Mini Apps、Memory Bank | 已实现 | `Screen.Workspaces`、`Terminal`、`Workflows`、`Plugins`、`Skills`、`MiniApps`、`MemoryBank`；对应页面包 |

导航集中在单个 `RouteActivity.kt` 中，Screen 类型和 entry 映射数量较大；新增页面时需要同时维护路由类型、参数和 entry，属于长期可维护性热点。

## 4. 核心功能地图

### 4.1 AI Provider/API 接入层

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| 核心 Provider 契约覆盖文本生成、流式生成、模型列表和 embedding 等能力 | 已实现 | `ai/src/main/java/me/rerere/ai/provider/Provider.kt` 的 `Provider` 接口 |
| 原生 Provider 类型为 OpenAI、Google、Claude | 已实现 | `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt` 的 sealed 类型；`ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt` 注册的 `openai`、`google`、`claude` |
| OpenAI 同时支持 Chat Completions 与 Responses API，且允许自定义 Base URL/路径 | 已实现 | `ProviderSetting.OpenAI`；`providers/openai/ChatCompletionsAPI.kt`、`ResponseAPI.kt` |
| Google 支持 Gemini API 与 Vertex/service account 路径 | 已实现 | `ProviderSetting.Google`；`GoogleProvider.kt`；`providers/google/ServiceAccountTokenProvider.kt` |
| Claude 支持消息 API 和 prompt caching 相关处理 | 已实现 | `ai/src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt`；`ai/src/test/java/me/rerere/ai/provider/providers/ClaudeProviderPromptCacheTest.kt` |
| 生成编排会执行输入/输出转换、工具循环、流式合并和审批等待 | 已实现 | `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt` 的生成循环；`app/src/main/java/me/rerere/rikkahub/data/ai/transformers` 下的 Transformer 实现 |

现有 Transformer 包括模板、思考标签、正则输出、提示词注入、文档转提示词、OCR、Base64 图片落盘、语音消息、时间与 Workspace 提醒，依据为 `app/src/main/java/me/rerere/rikkahub/data/ai/transformers` 下的实现类。

### 4.2 MCP、工具调用与权限控制

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| MCP 客户端支持 SSE 和 Streamable HTTP，支持 headers、工具同步、重连和 OAuth | 已实现 | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt` 的 `SseTransportServer`/`StreamableHTTPServer`；同目录 `McpManager.kt` 的连接、`syncTools`、`callTool` 和 OAuth 流程 |
| MCP OAuth 回调经自定义 URI 回到应用 | 已实现 | Manifest 的 `rikkahub://mcp-oauth-callback`；`McpOAuthCallbackActivity.kt` |
| MCP 与本地工具共享统一的审批状态模型 | 已实现 | `McpConfig.kt` 的 `McpTool.needsApproval`；`ToolSurfaceBuilder.kt`；`GenerationHandler.kt` 对 `ToolApprovalState` 的处理 |
| 交互式工具调用和 Headless Workflow 均有执行前控制 | 部分实现 | `GenerationHandler.kt`；`workflow/execution/WorkflowEngine.kt`；具体安全边界在私有维护记录中跟踪 |

本地/系统工具面实际包含屏幕无障碍操作、截图、SSH/SFTP、Workspace shell、文件读写、应用切换、使用情况、位置、相机、日历、短信、通知、媒体、音量、亮度、Wi-Fi、电话状态、闹钟、壁纸等。依据为 `app/src/main/java/me/rerere/rikkahub/data/ai/tools` 与 `tools/local` 的 Tool 构造函数，以及 `LocalTools.kt`/`SystemTools.kt` 的汇总注册。它们不能按“聊天功能”统一评估，后续应按工具逐项维护权限、审批和数据边界。

### 4.3 主动消息、定时任务、通知和后台运行

| 能力 | 状态 | 代码依据 |
| --- | --- | --- |
| 随机间隔主动消息；AlarmManager 主路径、WorkManager 兜底、重启恢复 | 已实现（默认关闭） | `ProactiveMessageSetting.kt` 的 `enabled = false`；`ProactiveMessageService.kt` 的 `scheduleNext`、`ProactiveMessageWorker`、`ProactiveMessageBootReceiver` |
| 主动消息上下文可汇总最近聊天间隔、时间、位置、应用使用、前台应用、当日通知和电量 | 已实现 | `ProactiveMessageService.kt` 的 `buildProactiveContext` |
| 主动消息 aggressive mode 可按屏幕/前台应用事件更频繁触发 | 已实现（默认关闭） | `ProactiveMessageSetting.aggressiveModeEnabled = false`；`DeviceEventAiTriggerService.kt` |
| 通知读取和通知发布 | 已实现 | Manifest 的 notification listener service；`RikkaNotificationListenerService.kt`；`NotificationPostTool.kt` |
| 工作流支持时间、连接、电源、电量、地理围栏、应用、通知、开机、屏幕和手动触发 | 已实现 | `workflow/model/TriggerSpec.kt` 的各 sealed variant；`workflow/trigger/TriggerRegistry.kt` 和各 Trigger 实现 |
| 工作流相互调用被明确禁止，避免无界链式触发 | 已禁用 | `workflow/model/WorkflowJson.kt` 对 `workflow_run` 返回 `workflow_chaining_disabled` |
| 每日插件 cron hook、Supabase 同步、设备事件采集、Web Server、Bot、语音通话等有独立后台 Service | 已实现（多项默认关闭） | `DailySummaryService.kt`、`SupabaseSyncService.kt`、`DeviceEventTrackingService.kt`、`WebServerService.kt`、`WeixinBotService.kt`、`QqBotService.kt`、`VoiceCallService.kt`；各自设置数据类的 `enabled = false` |
| 本地日记摘要的自动调度/补算已停用，转由外部 Edge Function 负责 | 已禁用 | `data/service/DiarySummaryService.kt` 的 `@Deprecated`、`rescheduleIfEnabled` 和 `checkAndGenerateMissingDiaries` 日志分支 |

Android 可能限制后台精确闹钟、前台服务类型和启动时机；源码中的 AlarmManager/WorkManager/Foreground Service 组合说明有兼容处理，但不同厂商 ROM 的可靠性仍需真机矩阵确认。

### 4.4 设备与生活上下文

| 能力 | 状态 | 代码依据 |
| --- | --- | --- |
| 位置、附近搜索 | 已实现（需权限/配置） | `LocationService.kt`、`DeviceLocationFetcher.kt`、`ExploreNearbyTool.kt`；Manifest 的定位权限 |
| 应用使用统计和前台应用 | 已实现（需特殊授权） | `AppUsageService.kt`、`AppUsageTool.kt`；Manifest 的 `PACKAGE_USAGE_STATS`、`QUERY_ALL_PACKAGES` |
| 通知历史/实时通知上下文 | 已实现（需通知访问授权） | `RikkaNotificationListenerService.kt`、`NotificationReadTool.kt` |
| 电量、Wi-Fi、电话状态、日历、短信、相机、媒体和系统控制 | 已实现（各需对应权限或系统能力） | `BatteryTool.kt`、`WifiInfoTool.kt`、`TelephonyInfoTool.kt`、`CalendarTool.kt`、`SmsTool.kt`、`CameraTool.kt`、`SystemTools.kt` |
| Gadgetbridge 健康数据读取和健康页面 | 已实现（依赖外部应用/数据可用性） | `GadgetbridgeService.kt`、`GadgetbridgeReader.kt`、`ui/pages/health/HealthPage.kt` |
| 设备事件及上下文上传到可配置远端 | 已实现（默认关闭） | `SystemToolsSetting.kt` 的 Supabase/采集开关；`SupabaseService.kt` 的 `collectAndUpload`；`SupabaseSyncService.kt` |

Manifest 请求了后台位置、短信、日历、麦克风、相机、通知、使用情况、悬浮窗、外部存储、精确闹钟、开机、无障碍、Shizuku 和多类前台服务能力，依据为 `app/src/main/AndroidManifest.xml`。这些能力并非都默认启用，但权限面本身是发布、商店审核和安全审查的核心范围。

## 5. 数据流与持久化概览

### 5.1 对话与生成数据流

1. Compose 或 Web UI 发起消息。依据：`ui/pages/chat/ChatPage.kt`；`web/routes/ConversationRoutes.kt` 的 `post("/{id}/messages")`。
2. `ChatService` 创建/更新会话并调用 `GenerationHandler`。依据：`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`；`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`。
3. `GenerationHandler` 选择 Provider、运行 Transformer、执行工具审批循环并合并流式结果。依据：`GenerationHandler.generate` 相关分支；`ProviderManager.getProviderByType`。
4. `ConversationRepository` 通过 Room DAO 保存 Conversation/MessageNode。依据：`ConversationRepository.kt`；`ConversationDAO.kt`；`MessageNodeDAO.kt`。
5. UI 通过 Flow/SSE 观察更新。依据：Android repository flow；`web/routes/ConversationRoutes.kt` 的 SSE 路由。

### 5.2 对话、角色设定与记忆

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| 对话由 MessageNode 列表组成，每个节点可保存多个候选消息并用 `selectIndex` 选择分支 | 已实现 | `data/model/Conversation.kt` 的 `Conversation.messageNodes`、`MessageNode.messages`、`selectIndex`、`currentMessages` |
| 对话与节点分别进入 Room；消息列表以 JSON 字符串保存 | 已实现 | `ConversationEntity.kt`；`MessageNodeEntity.kt` 的 `messages` 注释和字段 |
| Assistant 可配置模型、system prompt、采样参数、上下文长度、预设消息、正则、MCP、本地工具、Workspace、Skills、Lorebook/注入和外部记忆 | 已实现 | `data/model/Assistant.kt` 的 `Assistant` 数据类 |
| 简单记忆按 Assistant 或全局 ID 保存在 Room | 已实现 | `MemoryEntity.kt`；`MemoryRepository.kt` 的 assistant/global 查询和增删改 |
| Memory Bank 保存消息、人工记忆、摘要、embedding 状态，并支持关键词和向量召回 | 已实现 | `MemoryBankEntity.kt`；`MemoryBankService.kt` 的 `searchMemories`、`vectorRecall`、`saveManualMemory`、`saveChatMessage`、`saveAutoSummary` |
| 外部记忆支持远端消息/摘要读写和向量召回 | 已实现（依赖用户配置的外部服务） | `ExternalMemoryService.kt` 的 `saveMessage`、`searchMessages`、`saveDiarySummary`、`vectorRecallSummaries` |

### 5.3 本地存储与迁移

| 存储 | 状态 | 代码依据 |
| --- | --- | --- |
| Room 主库 `rikka_hub`，schema version 29，覆盖对话、消息节点、记忆、文件、收藏、媒体、文件夹、Workspace、Workflow、SSH 和安全审计 | 已实现 | `data/db/AppDatabase.kt` 的 `@Database`、entities 和 DAO；`di/DataSourceModule.kt` 的 `Room.databaseBuilder` |
| Room 同时使用 AutoMigration 和多段手写 Migration；没有发现 destructive fallback | 已实现 | `AppDatabase.kt` 的 `autoMigrations`；`DataSourceModule.kt` 的 `addMigrations(...)` |
| schema 1–29 均有导出文件 | 已实现 | `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/*.json` |
| Room migration 自动化测试只明确覆盖 11→12 | 部分实现 | `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt`；未发现其他 migration test |
| 全局 Settings 使用 Preferences DataStore，复杂结构以 JSON 保存，并有 V1/V2/V3 与备份 JSON 迁移器 | 已实现 | `data/datastore/PreferencesStore.kt`；`data/datastore/migration/PreferenceStoreV*Migration.kt`、`SettingsJsonMigrator.kt` |

### 5.4 导入、导出、备份与恢复

| 能力 | 状态 | 代码依据 |
| --- | --- | --- |
| 本地 ZIP 导出/恢复 | 已实现 | `BackupVM.exportToFile`/`restoreFromLocalFile`；`WebDavSync.prepareBackupFile`/`restoreFromBackupFile` |
| WebDAV 备份、列表、恢复、删除和连通性测试 | 已实现 | `data/sync/webdav/WebDavSync.kt`；`WebDavClient.kt`；`ui/pages/backup/tabs/WebDavTab.kt` |
| S3 兼容存储备份、恢复、删除和连通性测试 | 已实现 | `data/sync/S3Sync.kt`；`s3/S3Client.kt`、`AwsSignatureV4.kt`；`ui/pages/backup/tabs/S3Tab.kt` |
| ZIP 可包含 Settings、Room DB/WAL/SHM、上传文件、Skills，并在本地导出时包含插件设置/目录 | 已实现 | `WebDavSync.prepareBackupFile` 的 `settings.json`、数据库和目录写入逻辑；`includePlugins` 分支 |
| Chatbox 对话/Provider 导入和 Cherry Studio Provider 导入 | 已实现 | `ChatboxImporter.kt`；`CherryStudioProviderImporter.kt`；`BackupVM.restoreFromChatBox`/`restoreFromCherryStudio` |
| 模式注入和 Lorebook 支持原生 JSON及部分 SillyTavern 格式 | 已实现 | `data/export/ExportSerializer.kt` 的 `ModeInjectionExportSerializer`、`LorebookExportSerializer` 及 `tryImportSillyTavern*` |
| Android 系统备份/设备迁移规则的跨版本一致性 | 未确认 | Manifest 与 `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`；具体数据范围在私有维护记录中跟踪，实际行为需按 Android 版本验证 |

恢复逻辑会直接替换 Settings 和数据库文件并提示重启，依据为 `WebDavSync.restoreFromBackupFile` 和 `ui/pages/backup/components/BackupDialog.kt`。后续修改 schema 或 Settings 时必须同步验证“旧备份恢复 → 启动 → migration → 数据可读”的整链路。

## 6. Web UI 与 Android 原生部分

| 结论 | 状态 | 代码依据 |
| --- | --- | --- |
| Web UI 是 React 19/React Router/Vite 应用，构建产物复制到 Android `web` 模块 resources | 已实现 | `web-ui/package.json`；`web-ui/copy.ts`；`web/build.gradle.kts` 的 `buildWebUi` 输出目录 |
| Ktor 在设备内提供 SPA 静态资源和 `/api` | 已实现 | `web/src/main/java/me/rerere/rikkahub/web/Entry.kt` 的 `staticResources`/`singlePageApplication`；`app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt` 的 `route("/api")` |
| Web API 直接复用 Android 的 `ChatService`、`ConversationRepository`、`SettingsStore` 和 `FilesManager` | 已实现 | `WebServerManager.kt` 的构造参数；`configureWebApi` 参数与 route 注册 |
| Web UI 可进行对话列表/搜索、发送、编辑、分支、重新生成、停止、工具审批和文件操作 | 已实现 | `web/routes/ConversationRoutes.kt`、`FilesRoutes.kt`、`SettingsRoutes.kt`；`web-ui/app/services/api.ts` 与 `routes/c.$id.tsx` |
| Web Server 可配置启停、监听范围和 JWT 访问控制 | 已实现 | `PreferencesStore.Settings` 的 Web Server 设置；`WebServerManager.start`；`WebApiModule.configureWebApi` |

Web UI 不是独立后端，也不是 Android WebView 对原生页面的简单镜像；它是同一进程内、共用数据与 Chat 编排的另一套客户端。Web API 鉴权配置因此直接影响对话、文件和工具审批面的暴露范围。

## 7. 当前启用、禁用或疑似未完成的功能

| 项目 | 状态 | 代码依据与说明 |
| --- | --- | --- |
| MNN 本地 native 推理 | 已禁用 | `ai/build.gradle.kts` 的 externalNativeBuild 注释；MNN `.gitmodules` 条目没有进入当前源码树构建 |
| 本地日记自动调度/补算 | 已禁用 | `DiarySummaryService.rescheduleIfEnabled`、`checkAndGenerateMissingDiaries` 已 deprecated 且只记录停用信息 |
| Workflow chaining | 已禁用 | `WorkflowJson.parse` 拒绝 action 中的 `workflow_run` |
| 主动消息、aggressive trigger、Web Server、Supabase sync、微信/QQ bot | 已实现（可选能力） | `ProactiveMessageSetting`、`PreferencesStore.Settings`、`SystemToolsSetting`、`WechatBotSetting`、`QqBotSetting` 及对应 Service |
| 自动工具调用的安全控制 | 部分实现 | `GenerationHandler.kt`、`AgentTurnTracker.kt`；具体缺口在私有维护记录中跟踪 |
| Baseline Profile 场景覆盖 | 部分实现 | `app/baselineprofile/src/main/java/me/rerere/baselineprofile/StartupBenchmarks.kt` 与同目录 `BaselineProfileGenerator.kt` 仍有待补充交互的 TODO |
| Android 平台备份策略覆盖 | 部分实现 | Manifest 与备份规则资源已存在；跨版本覆盖和安全边界仍需完善 |
| `Provider.getDisplayName` 默认实现 | 部分实现 | `ai/src/main/java/me/rerere/ai/provider/Provider.kt` 默认返回 `"TODO"`；实际 UI 是否总由具体实现覆盖需逐 Provider 验证 |

“存在 Route/Service/Tool”只证明实现路径存在。涉及外部账号、厂商服务、无障碍、Shizuku、后台定位、地理围栏、Bot 或外部记忆的功能，均未在本次静态审计中使用真实凭据或真机验证。

## 8. 风险与未知项

### 8.1 安全与隐私

静态审计已发现需要优先处理的安全敏感项，覆盖本地敏感数据保护、备份与迁移边界、日志最小化、Web 访问控制、工具授权，以及高权限 Android 能力。为避免在问题修复前公开具体攻击面、默认暴露方式或敏感数据范围，详细代码定位、风险边界和修复方案仅在私有维护记录中跟踪。

公开仓库应继续保留以下高层原则：

- 敏感数据必须采用与普通业务数据不同的保护边界。
- 备份、导出、系统迁移和日志都必须遵循数据最小化。
- Web、MCP、自动工具调用和 Headless Workflow 必须采用安全默认值与分层授权。
- 高风险权限、exported 组件及设备控制能力必须单独审查并进行真机验证。

### 8.2 兼容性与维护性

| 风险 | 级别 | 状态 | 代码依据 |
| --- | --- | --- | --- |
| 编译链同时依赖 Android/JDK 与 Node/pnpm，任一 lockfile、工具版本或静态产物链异常都会阻断 APK | 中高 | 已确认 | `web/build.gradle.kts` 的 `preBuild` 依赖；`web-ui/pnpm-lock.yaml`；Debug workflow 的双工具链步骤 |
| Material submodule 是源码级强依赖；MNN 却保留 SSH submodule 声明，容易误导通用 checkout | 中 | 已确认 | `.gitmodules`；`material3/build.gradle.kts`；`ai/build.gradle.kts` |
| alpha/snapshot/JitPack 依赖增加可重复构建和上游消失风险 | 中高 | 已确认 | `gradle/libs.versions.toml` 中 Material3 alpha、Navigation3、snapshot 与 Git hash 坐标 |
| compile/target SDK 37 较新，高权限、前台服务和后台启动行为需持续跟踪平台变化 | 中高 | 已确认 | `app/build.gradle.kts`；Manifest 的 FGS、exact alarm、background location 等声明 |
| Room 已到 version 29，但 migration instrumentation test 覆盖很窄 | 高 | 已确认 | `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/1.json` 至 `29.json`；仅发现 `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt` |
| 单一 `RouteActivity.kt` 承担大量 Screen 和 entry 映射 | 中 | 已确认 | `RouteActivity.kt` 的 `sealed interface Screen`、`entryProvider` |
| Workflow trigger 注释称 19 个 variant，实际 sealed class 已包含更多项，说明规格与代码可能漂移 | 中 | 已确认 | `workflow/model/TriggerSpec.kt` 的类级注释与实际 variants |
| 多个后台调度机制并存，可靠性依赖 Android/ROM 策略 | 中高 | 未确认 | `ProactiveMessageService`、`DailySummaryService`、`SupabaseSyncService`、`KeepAliveService`、Workflow triggers 的 Alarm/Work/FGS 组合 |

### 8.3 测试未知项

- 已实现：AI 消息/Provider 映射、ToolApprovalState、部分 Transformer、TTS 等存在 JVM 测试。依据：`ai/src/test`、`app/src/test`、`speech/src/test`。
- 部分实现：多个模块只有 `ExampleUnitTest`/`ExampleInstrumentedTest`，不能证明业务覆盖。依据：各模块 `src/test` 与 `src/androidTest`。
- 未确认：MCP OAuth/重连、Web API 鉴权、备份跨版本恢复、全部 Room migration、主动消息、Workflow 触发、权限拒绝/撤回、后台限制和真机系统工具缺少本次可见的系统性端到端证据。

## 9. 后续建议

### 必须先处理

1. 建立敏感数据资产清单与设备端保护方案，并为现有数据设计可回滚迁移。
2. 重新定义备份、导出和系统迁移的安全模型，加入完整性、保密性与恢复校验。
3. 实施日志最小化和统一脱敏，并用自动化测试防止敏感上下文进入日志。
4. 为 Web Server、MCP、自动工具和 Headless Workflow 建立安全默认值、能力分级与不可绕过的高风险控制。
5. 为 Room 1→29 的实际升级路径和“旧备份恢复后升级”建立 migration test matrix。依据：`AppDatabase.kt`、`app/schemas`、当前单一 migration test。
6. 对所有高风险 Android 权限、exported 组件和设备控制能力做逐项威胁建模与真机验证。

### 建议处理

1. 将导航注册按功能域拆分，并加入 route 参数/深链测试。依据：`RouteActivity.kt`。
2. 固定并记录 Node/pnpm/JDK/Gradle/NDK/CMake 兼容矩阵，缓存仍以 lockfile 和 Wrapper 为准。依据：`web/build.gradle.kts`、`web-ui/package.json`、各 Gradle 配置。
3. 审查并逐步替换 snapshot/alpha/JitPack 依赖；无法替换时保留制品或校验来源。依据：`gradle/libs.versions.toml`。
4. 清理 MNN 的失效/停用 submodule 表述，或写清恢复条件，避免 CI/开发者误初始化 SSH 依赖。依据：`.gitmodules`、`ai/build.gradle.kts`。
5. 为 MCP transport/OAuth、Web API、Workflow triggers、主动消息、后台服务和权限撤回补充可重复测试。依据：对应实现存在，但测试目录未见同等覆盖。
6. 恢复屏幕自动化动作计数或删除无效调用，避免形成虚假的安全控制印象。依据：`AgentTurnTracker.recordAutomationAction()`。

### 可暂缓

1. 在没有明确本地推理需求前继续保持 MNN native 构建禁用。依据：当前 AI Provider 链已独立工作，MNN 未进入 Gradle。
2. 在没有明确多工作流编排需求前继续禁止 workflow chaining。依据：`WorkflowJson` 已以防无界链为理由拒绝。
3. Baseline Profile 的高级场景可在稳定性、安全和数据迁移完成后扩充。依据：现有 TODO 只影响性能覆盖，不是数据正确性主路径。
4. 本地日记摘要是否恢复，应在外部记忆边界、隐私模型和数据所有权明确后再决定。依据：`DiarySummaryService` 当前明确转交外部执行。

## 10. 本次仍无法确认的事项

- 需要真实厂商账号/外部服务的 Provider、MCP OAuth、WebDAV、S3、Supabase、Bot 和外部记忆互操作性。
- Android 26–37 及不同 ROM 下 exact alarm、WorkManager、前台服务、后台定位、开机恢复和通知监听的可靠性。
- 无障碍、Shizuku、UsageStats、地理围栏、Gadgetbridge、通话/录音等在权限被拒绝、撤回或系统回收后的行为。
- 旧版本数据库和真实历史备份跨多个 schema/settings 版本的恢复完整性。
- Release 签名、发布渠道和商店权限政策；本次只审计 Debug/源码构建，不接触任何正式凭据。
- 外部 Edge Function、MCP server 或其他远端实现的安全与数据处理行为；它们不在本仓库静态源码审计范围内。
