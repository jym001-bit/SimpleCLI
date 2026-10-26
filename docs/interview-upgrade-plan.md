# PaiCLI 面试题对齐升级计划

来源：2026-10-09 面试文章《海康 Agent 面试 7 问》（toBeBetterJavaer `docs/src/sidebar/itwanger/ai/haikang-robot-agent-mianshi.md`）。文章按面试官期望的方案作答，下面是当前代码（HEAD `4ae374d`）和文章答案之间的差距，做完一项勾一项。路径相对 `src/main/java/com/paicli/`。

## P0：安全

- [x] **项目级 MCP 配置首次启动前确认**（文章 03 题）
  - 现状：`mcp/config/McpConfigLoader.java:27-55` 会加载项目目录下的 `.paicli/mcp.json`，启动时直接执行里面的命令。克隆一个自带 MCP 配置的陌生仓库，PaiCLI 一启动就会执行仓库作者写的命令
  - 目标：项目级配置里的 server 第一次启动前让用户确认，确认过的“命令 + 参数 + env + url + headers”记录哈希（cwd 固定为项目目录，配置里改不了，不参与计算），内容变化后重新确认；用户级配置不受影响
  - 做法：确认用 `/mcp trust <name>`（和 `/skill trust` 一致），没确认的 server 状态是“待确认”，启动时提示；交互式 CLI 是唯一会启动 MCP 的入口，没人确认就一直不启动
  - 注意：`load()` 用 `putAll` 合并，项目配置可以用同名 server 覆盖用户配置，所以确认要按“项目级来源 + 配置内容”记，不能只按名字；env 必须算进哈希（`NODE_OPTIONS=--require ./evil.js` 一样能执行任意代码）；记录方式参考 `skill/SkillStateStore.java` 的 `trustedProjectSkills`；微信、Runtime API、评测这类没人确认的场景默认不启动
- [x] **MCP 子进程环境变量白名单**（文章 03 题）
  - 现状：`mcp/transport/StdioTransport.java:32-49` 用 ProcessBuilder 默认继承父进程全部环境变量，再 `putAll` 配置里的 env，用户机器上的各种 API Key 都会传给 MCP 服务
  - 目标：先清空环境变量，只保留运行必需的变量，再加上配置里给这个 server 单独配置的 env
  - 注意：只留 PATH/HOME/LANG/TMPDIR 不够，国内很多人靠代理跑 `npx`。至少保留 `HTTP(S)_PROXY`/`NO_PROXY`（大小写都要）、`USER`、`SHELL`、`LC_*`、`NODE_EXTRA_CA_CERTS`、`npm_config_registry`；Windows 还要 `SystemRoot`、`APPDATA`、`USERPROFILE`、`COMSPEC`、`PATHEXT`。做完用本机的 chrome-devtools MCP 实际跑一次

## P1：工具参数校验

- [x] **执行前按 JSON Schema 统一校验参数**（文章 01 题）
  - 现状：`tool/ToolRegistry.java:1463-1466` 用 `readTree` 解析后把所有值 `asText()` 成字符串，没有通用的必填、类型、枚举校验，各工具自己临时检查；`Param` 记录（`:1933`）只有 name/type/description/required，生成的 Schema 没有 enum、范围；JSON 解析失败只返回 `"工具执行失败: " + 异常信息`；MCP 工具参数不做校验，原样发给 server（`mcp/McpClient.java:101-108`）
  - 目标：引入 networknt json-schema-validator，内置工具和 MCP 工具都在执行前按注册时的 Schema 校验；校验失败返回字段级错误（“参数 path 缺失”“max_results 必须是整数”）；JSON 解析失败单独返回“参数不是合法 JSON”并附上原文片段；`Param` 支持 enum 和数值范围
  - 做法：内置工具只拦结构问题（缺参数、传了对象 / 数组、不在 enum 里），单值类型和 null 仍交给工具自己解析——评测 F1/F2 的独立复算按这套语义还原 read_file 看到的内容（`"  +1 "`、阿拉伯-印度数字、null 都有用例），严格卡类型会让复算和实际对不上；MCP 按原始 Schema 严格校验（`McpSchemaSanitizer` 清洗后的那份会去掉 null 选项、放宽复杂的 anyOf / oneOf，拿来校验会变松或变严）；数值参数不加范围，工具现在会把超范围的值收回到合法范围，改成拒绝反而让弱模型重试；enum 用在 `create_project.type`、`save_memory.scope`；Jackson 从 2.16 升到 2.18.3 与校验库对齐，`-Pquick` 失败名单和升级前完全一致
  - 注意：MCP server 的 Schema 可能写得不规范或用了较新的 draft，加载失败时跳过校验并记日志，不能让工具整个不可用；内置工具现在靠 `parseInt` 能接受 `"top_k": "5"` 这种字符串数字，严格校验会让弱模型反复重试，开启 `typeLoose`

## P1：MCP 生命周期

- [x] **stdout 单行解析容错**（文章 03 题）
  - 现状：`mcp/transport/StdioTransport.java:115-135` 的 try/catch 包在整个读循环外面，MCP 服务往 stdout 打一行日志，读线程就退出，之后所有请求都只能等 60 秒超时，server 状态还显示 READY
  - 目标：每一行单独 try/catch，非 JSON 的行写进 stderr 环形缓冲并跳过，读线程继续
- [x] **回复 server 主动发来的请求**（文章 03 题）
  - 现状：`mcp/jsonrpc/JsonRpcClient.java:99-103` 把同时带 id 和 method 的消息当成响应处理，`pending` 里找不到就丢弃，不回复
  - 目标：识别 server 发来的请求，`ping` 回空结果，不支持的方法回 `-32601`
  - 注意：问题比“丢弃不回复”更严重。`handleMessage` 先按 id 去 `pending` 里找，不看 `method`；server 的请求 id 也从 1 开始，会和我们自己的请求撞号，把 server 的 ping 当成我们某个请求的回复（结果为空）。`asLong()` 还会把字符串 id 变成 0。要先看有没有 `method`，有就按请求处理，没有才去 `pending` 匹配
- [x] **进程退出检测与退避重启**（文章 03 题）
  - 现状：没有检测子进程退出，也没有健康检查，只能 `/mcp restart` 手动重启（`mcp/McpServerManager.java:180-192`）；`docs/phase-11-mcp-advanced.md:29,32` 把 crash 自动重启和 heartbeat 列为不做，`:146` 把指数退避重启列为后续项
  - 目标：用 `Process.onExit()` 注册回调，子进程退出后标记 ERROR、移除工具、让 pending 请求立即失败；按 1s、2s、4s 指数退避自动重启，连续失败 3 次停止并提示用户；定期发 ping 检测卡死。同步更新 phase-11 文档
  - 做法：ping 每 30 秒一次、只发给本地 stdio server，server 有请求在处理时跳过，连续 2 次不回才判定卡死；稳定运行 60 秒以上再崩溃，重启计数从头算；后台提示等下一个输入框前打印
- [x] **结束整棵进程树、清理孤儿进程**（文章 03 题）
  - 现状：`StdioTransport.close()`（`:85-113`）只对直接子进程 destroy/destroyForcibly，npx 拉起的 node 进程会残留；JVM 被 kill -9 时 shutdown hook（`cli/Main.java:315`）不执行，没有其他清理手段。`eval/benchmark/BenchmarkSubprocess.java:54-156` 已有 `ProcessHandle.descendants()` 的写法可以复用
  - 目标：关闭时先取 `descendants()`，从叶子进程开始依次结束；启动时把子进程 PID 写入 `~/.paicli/mcp-pids`，下次启动先清理仍存活的旧进程
  - 注意：PID 会被复用，记录时一起存启动时间（`ProcessHandle.info().startInstant()`）和命令，清理前核对，对不上不杀；可能同时开着多个 PaiCLI，要记下所属 PaiCLI 的 PID，只在它已退出时清理；`BenchmarkSubprocess` 按 PID 倒序结束并不是真正的“从叶子开始”（PID 会回绕），能复用的是“先记下整棵进程树、再结束根进程”
- [x] 顺带：`McpServerManager.java:459` 创建的 `NotificationRouter` 没有保存引用，`close()` 从未被调用；`docs/phase-10-mcp-core.md:154` 写 initialize 超时 30 秒（代码是 60 秒），`:89` 写关闭前先发 shutdown 通知（代码已去掉），文档需要同步

## P2：代码索引性能

- [x] **按文件哈希做增量索引**（文章 04 题）
  - 现状：`rag/CodeIndex.java:104-108` 每次 `/index` 先 `clearProject()` 再全量重建，没有 hash/mtime 判断；所有 entry 先攒在内存里再统一落库（`:71-72`）；读取文件没有大小上限
  - 目标：`code_chunks` 记录文件内容哈希，只重建哈希变化的文件，删除文件对应的 chunk 和 relation；超过 1MB 的文件跳过；按文件分批落库
- [x] **Embedding 批量调用**（文章 04 题）
  - 现状：`rag/EmbeddingClient.java:46-61` 每个 chunk 发一次 HTTP 请求
  - 目标：Ollama 用 `/api/embed` 的数组输入，OpenAI 兼容接口用数组 input，每批 10 条起
- [x] **并行解析**（文章 04 题）
  - 现状：`CodeIndex.java:77-102` 单线程顺序解析
  - 目标：每个线程用 ThreadLocal 持有自己的 JavaParser 实例（官方说明实例不是线程安全的），默认 4 个线程，可配置
  - 做法：指纹单独存 `indexed_files` 表（文件路径 + SHA-256 + Embedding 模型），换模型全量重建，没有指纹的旧索引第一次整体重建；Java 文件只解析一次，分块和关系分析共用语法树；Embedding 跨文件批量调用（默认 32 条一批），一批文件一个事务，某批失败时保留这些文件的旧索引、下次重试（原来 Ollama 没开时跑 /index 会把索引清空）
  - 实测（本地 Ollama nomic-embed-text，pmhub 1892 个文件）：改动前全量 218.9 秒；改动后第一次全量 128.7 秒（4 个超 1MB 文件跳过），没改动再跑 0.8 秒。全量只快 1.7 倍，时间主要花在 Ollama 计算向量上

## P2：内存

- [x] **请求体流式写出**（文章 05 题）
  - 现状：`llm/AbstractOpenAiCompatibleClient.java:112-113` 每轮把完整对话历史 `buildRequestBody(...).toString()` 成一个字符串，再交给 OkHttp 编码成字节数组，长上下文时在 G1 里是巨型对象
  - 目标：自定义 OkHttp `RequestBody`，在 `writeTo(BufferedSink)` 里用 Jackson `JsonGenerator` 直接写出
  - 实测：PaiCLI 自己的源码当工具结果拼出约 1M token 的对话（350 万字符代码）加 3 张 1MB 截图，请求体 7.9MB；原做法每次请求分配 90.5MB（建树 4.7 + toString 54.5 + 转字节 31.3），`JsonRequestBody` 4.7MB，请求体逐字节一致
  - 做法：先写一遍计数流拿长度，照常带 Content-Length，不用分块传输；emoji 用 `COMBINE_UNICODE_SURROGATES_IN_UTF8` 原样写出（默认会转义成代理对，内容等价但字节不同）
  - 注意（动手前的两个顾虑，都已解决）：一是这项原来没有实测数据，现在已补上，见上面的“实测”；二是担心改完只能走分块传输、个别代理网关不接受，现在先写计数流拿到长度，照常带 Content-Length

## 同步修改

- [x] 进程生命周期做完后更新 `AGENTS.md` 的“下一步”和“还没做到的”（都写着 server 断线恢复 / 自动重启没做）
- MCP 改动按 `AGENTS.md` 的同步表一起改 HITL、AuditLog、提示词、文档、测试，以及 `docs/articles/paicli-interview-mcp.md`

## 执行顺序

1. 第一批：项目级 MCP 确认、环境变量白名单、stdout 单行容错、server 请求识别
2. 第二批：进程退出检测与退避重启、结束整棵进程树
3. 第三批：参数 Schema 校验
4. 最后：RAG 三项、请求体流式写出

## 实测记录（2026-10-09，文章 04、05 题的数据来源）

- 环境：Apple M3 Max，JDK 21.0.9，`-Xmx2g`，javaparser-core 3.28.0，配置和 `rag/JavaSourceParser.java:16-17` 相同（Java 17 语言级别）
- 样本：本地 flexmark-java、PaiFlow、pmhub、ruoyi-ai、paicoding 五个项目，按 `CodeIndex` 的目录过滤规则取 `.java` 文件，共 5874 个文件、2061 万字符、58 万行；解析出 6297 个类、30809 个方法、189455 处方法调用，1 个文件解析失败
- 解析 + findAll 类/方法/方法调用：单线程首轮 5.5 秒，预热后约 4.6 秒；ThreadLocal 实例并行 2 线程 2.4–2.6 秒、4 线程 1.9–2.0 秒、8 线程 2.3–2.4 秒、16 线程约 3.0 秒（`-Xmx8g` 结果相同）
- 单线程一轮分配约 4.8GB；整个测试 Young GC 70 次，累计约 0.1 秒，Old GC 0 次
- Embedding：本地 Ollama `nomic-embed-text`，取 PaiCLI 源码里的 200 个真实方法体（平均 454 字符），逐条 `/api/embeddings` 平均 16.4ms/条；`/api/embed` 每批 10 条 8.1ms/条，每批 50 条 7.2ms/条。按 37106 个 chunk 估算，逐条约 10 分钟
