# PaiCLI 工具意图误判事故与升级方案：独立评审材料

记录日期：2026-09-25（Asia/Shanghai）  
项目：`/Users/itwanger/Documents/GitHub/paicli`  
材料编写者：本次参与排查、修改代码并提出方案的 Codex 助手。

这份材料同时记录事故、助手的处理过程和待评审方案。编写者不是独立裁判，文中的方案和归因应接受质疑。请裁判依据证据评审，可以否决方案，也可以认为局部修复比结构调整更合适。

## 1. 用户要解决的问题

用户在 PaiCLI 中提出正常的文件操作、编译运行要求，终端却显示 DeepSeek 的 DSML 工具调用文本，然后返回输入状态，没有真正执行对应操作。

用户连续追问：还会不会发生、到底哪里有问题、用正则判断是不是不合理、应该如何升级。助手先后做了两次局部修复，后来承认这些修复仍依赖关键词识别，提出撤掉自然语言动作识别对工具列表的总开关。

**当前边界：两次局部修复已经写入代码、做过离线测试并打包；撤掉总开关的结构调整仅是方案，尚未实施。用户本轮只要求整理评审文件。**

## 2. 事故 A：用户回复选项“1”后，工具数量变成 0

### 2.1 交互过程

1. 用户要求把 `Hello.java` 中的 `Hello World` 改成 `Hello PaiCLI`。
2. PaiCLI 检索后报告没有找到文件，给出编号选项。
3. 第 1 项是先创建 `Hello.java`，写入 Hello World 版本，再修改。
4. 用户回复 `1`。
5. 模型在正文中输出创建文件的 DSML，程序没有执行。

上一条助手回复包含：

```text
1. **需要我先创建**：比如在项目里建一个 `Hello.java`
   （内容为 `System.out.println("Hello World")`），再执行修改；
2. **文件在别处**：告诉我具体路径……
3. **你想改的是 `HelloController.java`**……
```

### 2.2 日志证据

来源：`/Users/itwanger/.paicli/logs/paicli.log`。

```text
2026-09-25 10:48:02.010 ... LLM request context [react iteration=7]:
messages=16, ... tools=46, toolsSchemaTokens=5580, estimatedTotal=10670

2026-09-25 10:48:29.240 ... LLM request context [react iteration=1]:
messages=18, ... tools=0, toolsSchemaTokens=0, estimatedTotal=5326

2026-09-25 10:48:35.756 ... ReAct run finished:
inputTokens=6277, outputTokens=1319, reasoningChars=2748, answerChars=417
```

原始会话账本：

`~/.paicli/history/raw/session-1790304090124-70341613-8c38-4285-940e-40cab55856c7.jsonl`

其中 sequence 19 是用户的 `1`，sequence 20 的 assistant 正文包含以下真实格式：

```text
<｜｜DSML｜｜ calls>
<｜｜DSML｜｜ invoke name="write_file">
<｜｜DSML｜｜ parameter name="path" string="true">Hello.java</｜｜DSML｜｜ parameter>
<｜｜DSML｜｜ parameter name="content" string="true">public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello World");
    }
}
</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
</｜｜DSML｜｜ calls>
```

注意：原始工具名是 `write_file`。截图中的 Markdown 渲染可能让下划线不明显，不能据截图认定模型调用了 `writefile`。

### 2.3 助手第一次修复

- 在 Agent 中保存上一轮策略，并从现有 `conversationHistory` 读取末条助手正文。
- 新增 `fromConversationReply()`：编号必须匹配上一条编号选项；“继续”“好的”等要求上一轮已经被判定为任务。
- 续接恢复动作资格，保留上一轮禁网限制；不继承旧 URL、浏览器操作权限或显式记忆写入授权；`/clear` 清除续接状态。
- DeepSeek DSML 解析原先只接受 `tool_calls`，且不接受标记后的空格；补充 `calls` 容器和空白兼容。
- DSML 仍要求只有一个完整块、首尾容器名一致、工具名在本轮暴露集合中、参数严格解析。没有通过猜测来重命名工具。

验证：98 项相关离线测试通过，其中有模拟 SSE 响应、真实临时目录文件创建与修改的回放。证据文件为 `/tmp/paicli-followup-tests.log`。

**证明范围：这组测试证明被覆盖的回复和协议格式能正常处理，不证明任意中文表达都能识别，也不证明真实模型一定正确理解任务。**

## 3. 事故 B：明确的多步骤指令再次被过滤

用户输入原文：

```text
进入 demo 目录，编译并运行 Hello.java
```

日志：

```text
2026-09-25 11:12:50.958 ... LLM request context [react iteration=1]:
messages=22, ... tools=0, toolsSchemaTokens=0, estimatedTotal=5825

2026-09-25 11:12:51.997 ... ReAct run finished:
inputTokens=6990, outputTokens=132, reasoningChars=255, answerChars=159
```

原始账本：

`~/.paicli/history/raw/session-1790305232921-df467bb7-399e-4ca4-88bc-a34556ab3e95.jsonl`

真实响应正文：

```text
<｜｜DSML｜｜ calls>
<｜｜DSML｜｜ invoke name="list_dir">
<｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
</｜｜DSML｜｜ invoke>
</｜｜DSML｜｜ calls>
```

这次格式已在第一次修复的兼容范围内，但 DSML 转换要求本轮存在开放工具。由于请求的工具集合为空，转换不会执行。编译运行也没有发生。

### 3.1 助手第二次修复

旧判断主要检查整句开头，既没有识别“进入”，也没有检查逗号后的“编译并运行”。助手继续扩展了规则：

- 检查逗号、分号、句号和换行分隔的后续分句。
- 识别“进入 … 目录”“切换到 … 文件夹”“在 … 目录下”等表达。
- 增补“删掉”“移动”“重命名”和英文 `compile` 等动作词。
- 新增分句检查时排除配对引文、代码块和明确标注的摘录。

验证：65 项相关离线测试通过；用户原话的模拟 SSE 回放会执行真实临时目录的 `list_dir`，并将含 `Hello.java` 的结果送回模型。证据文件为 `/tmp/paicli-clause-tests.log`。

**该回放没有调用真实 DeepSeek，也没有实际编译运行 Java；它验证的是请求工具暴露、DSML 转换和目录工具执行。第二次修复仍然依赖正则与词表，不能据此承诺不会再误判。**

## 4. 当前实现为什么会出现这种结果

以下为当前源码的关键逻辑摘录，省略无关部分：

```java
// TurnToolPolicy 构造时
this.actionable = forceActionable || looksActionable(actionableInput);

// 发送模型请求前过滤工具定义
if (definitions == null || definitions.isEmpty() || !actionable) {
    return ToolExposure.none();
}

// 工具执行前再次检查
if (!actionable) {
    return Decision.deny(ReasonCode.NO_ACTION, ...);
}
```

Agent 把 `toolExposure.definitions()` 传给 `llmClient.chat()`，所以自然语言判断的误判会直接变成请求里没有工具。

DeepSeek 适配器在 `tools == null || tools.isEmpty()` 时跳过 DSML 转换；没有 native `tool_calls` 时，Agent 按普通正文结束本轮。这些行为共同解释了“有一段调用文字，但没有操作”的表象。

### 4.1 已确认与尚未确认

已确认：

- 两次出错的请求日志都显示 `tools=0`。
- 原始账本都包含 DSML 正文。
- 旧动作识别规则覆盖不了对应用户输入。
- 工具暴露和 DSML 转换的源码符合上述结果。

因果解释的边界：

- “未提供 tools 导致模型把调用写成正文”与日志、代码和回放一致，是合理解释。
- 没有进行真实服务端的控制变量实验，不能断言 DeepSeek 在提供 tools 时绝不会输出 DSML，也不能声称模型端不存在其他异常。
- DSML 的结构、工具白名单检查只证明格式与工具集合符合要求，不证明该段正文在语义上一定应当执行。例如正文引用一个合法调用示例，也是需要单独审视的边界。

## 5. 原有规则的目的和产品约束

`AGENTS.md` 要求：用户只给裸标题、主题或摘录且没有动作时先澄清，本轮不调用工具。现有确定性动作门槛旨在避免模型根据一个标题自行搜索、猜 URL 或执行其他操作。

其他现有约束包括：

- 只从用户实际提交原文获取相关权限依据，不能把文件或 MCP resource 的展开内容当成用户指令。
- `web_fetch` 和浏览器导航 URL 只来自本轮用户原文或本分支搜索返回的结构化 URL。
- 明确禁网、项目路径限制、命令检查和 HITL 等分别执行。
- HITL 默认关闭；通用产品级容器/VM 沙箱尚未交付，命令沙箱默认关闭。

**因此，撤掉动作门槛不是纯粹的体验优化：它也会改变裸标题场景的防误操作机制，必须明确评审这一行为变化。不能因为仍有路径检查和可选审批，就称风险已经完全消除。**

## 6. 助手的处理失误及自我修正

助手首先修复了“1”续接和 DSML 格式，又在第二次事故后扩展分句与动作词。两次修复都有针对性测试，但仍在用规则枚举开放式自然语言表达。

用户质疑后，助手承认：

> “前两次修复补了遗漏场景，还没有解决判断方式本身的问题。”

> “我前面连续补正则，方向确实不对，应该先改这个设计。”

上述自评也不是最终结论。裁判应判断：这些局部修复是否是合理止血；助手是否过早把问题推广成“应删除门槛”；有没有更好的渐进设计；以及测试与交付表述是否充分、诚实。

## 7. 待评审方案：模型理解意图，代码控制执行权限

**本节全部是提议，尚未实施。**

### 7.1 撤掉动作识别对工具列表的总开关

普通对话根据模型能力和实际权限提供工具，不再要求用户表述先命中动作词表。移除因 `looksActionable()` 未命中而清空工具或返回 `NO_ACTION` 的逻辑，并清理随后增加的续接词、编号和分句资格判断。

这不意味着删除全部正则。命令参数、协议格式等结构化输入仍可用正则；禁网、URL 和权限规则也需要单独保留及评审，不能连带移除。

实施风险：现有续接代码除了动作资格，还保留上一轮禁网限制。移除时必须将这类限制保留到独立的会话约束机制，否则“删除多余规则”会意外丢失保护。目前该独立机制的设计尚未给出。

### 7.2 在正常模型对话中判断执行或澄清

提示词明确：明确任务执行；选择上一轮选项则按选项继续；目标或对象不明确则澄清；单独标题、摘录、引用文字不自行扩展为操作任务。

复用当前轮模型与现有对话历史，不默认增加一次意图分类模型请求，也不维护影子对话副本。

行为变化：原来“裸标题不调用工具”的代码门禁会变成模型行为要求。提示词不能提供同等的确定性保证。如果产品仍要求这一规则是硬约束，这个方案本身就不完整，需要另一个可实施的确认或权限机制。

### 7.3 保留独立执行边界

所有实际调用仍由现有工具执行流程检查：工具是否开放、参数是否合法、路径范围、URL 来源、禁网约束、适用审批和执行结果边界。三条 Agent 执行路径不能绕开共享工具执行器。

风险说明：工作区内的错误写入、允许范围内的错误命令，也可能通过路径或参数检查。HITL 默认关闭时，“权限合法”不能替代“符合用户意图”。这部分风险必须由裁判评价，不能用“模型只决策、代码执行”一句话带过。

### 7.4 异常输出可见化

保留严格的 DSML 兼容；无法转换时明确提示“工具调用未执行”，不要让用户把一段调用文字误认为操作成功。

未解决细节：怎样区分真实失败的调用文本与用户要求展示的 DSML 示例？应在哪一层提示，如何保持流式输出与历史消息一致？未知或畸形块现在保持原文，改变该行为需要补充设计和测试。

## 8. 验收建议及不能混淆的验证层次

| 场景 | 确定性测试能检查什么 | 仍需模型行为验证什么 |
|---|---|---|
| `1`、`继续`、`删掉` | 不因关键词未命中而清空工具 | 正确理解指代，不擅自选择删除对象 |
| 多步骤编译请求 | 工具定义、解析、执行分发完整 | 识别真实目录、正确编译与验证 |
| 裸标题、摘录、引用 | 不把展开内容当权限来源 | 澄清而不是擅自行动 |
| 越界路径、未授权 URL | 执行层确实拒绝 | 不宣称被拒绝操作已成功 |
| 不合法 DSML | 不转换、不执行、准确提示 | 正文示例不被误判成失败调用 |
| 续接原来的禁网任务 | 约束正确延续，不能意外放开 | 不建议或声称执行被禁止动作 |

不能通过 mock 模型始终返回“请澄清”的测试，证明真实模型面对裸标题一定澄清。现有 98/65 项离线测试也不是升级方案的验收成绩，更不是正式 AgentBench 结果。

后续若需真实模型行为样本，应另行明确范围与成本。本次没有运行真实模型评测，也没有恢复已暂停的 benchmark。

## 9. 请求裁判回答的问题

1. 两次事故的归因是否充分？还有什么证据缺口或替代解释？
2. 正则作为工具资格门槛是否应保留、缩小范围，还是移除？请说明适用边界。
3. 助手先补续接、再补分句的处理，是合理止血还是不必要的补丁堆积？
4. 待评审方案是否把一个确定性的产品约束降级成了提示词建议？这种取舍是否可以接受？
5. HITL 默认关闭、命令沙箱默认关闭时，执行层现有检查是否足以支持放宽工具暴露？
6. 若不接受该方案，有什么实现复杂度、额外时延和费用可接受的替代方案？例如只对副作用操作确认、显式任务模式、带澄清状态的交互设计、独立意图分类等；无需局限于这些候选。
7. DSML 正文转工具是否还存在语义歧义，应该怎样限制？
8. 应怎样定义可重复的验收，避免把 mock 测试通过当成真实模型行为正确？
9. 给出建议结论：接受、附条件接受或拒绝；指出必须先补的设计与测试。请区分已实现修复与未实现方案。

## 10. 源码与证据索引

以下路径相对 PaiCLI 仓库；行号为记录时位置，后续编辑可能变化。

| 文件 | 关注位置 |
|---|---|
| `src/main/java/com/paicli/tool/TurnToolPolicy.java` | `actionable` 构造约 178 行；`fromConversationReply` 约 234 行；`expose` 拦截约 302 行；执行侧 `NO_ACTION` 约 431 行；`looksActionable` 约 609 行；分句补丁约 650 行 |
| `src/main/java/com/paicli/agent/Agent.java` | 策略构造约 176 行；工具暴露与请求约 227 行；工具执行约 859 行 |
| `src/main/java/com/paicli/llm/DeepSeekClient.java` | `normalizeDsml` 约 200 行；`parseDsml` 约 227 行；流式过滤器 |
| `src/test/java/com/paicli/tool/TurnToolPolicyTest.java` | 续接、多分句、标题、引用及权限相关测试 |
| `src/test/java/com/paicli/tool/TurnToolPolicyMemoryGuardTest.java` | 记忆写入授权与外部上下文测试 |
| `src/test/java/com/paicli/agent/AgentWebSearchDecisionTest.java` | Agent 工具暴露、裸标题与展开内容测试 |
| `src/test/java/com/paicli/llm/DeepSeekConversationReplayTest.java` | 两次事故的离线 SSE 回放和 DSML 反例 |
| `AGENTS.md` | Web 与浏览器规则、HITL、沙箱默认值、验证要求 |

日志和原始会话账本包含其他用户内容，评审本问题不需要外发整个文件。本材料只摘录必要证据，不包含 API Key。

## 附录：同一轮协作中的其他故障

这些不是两次 `tools=0` 的根因，但与助手整体工程处理质量有关：

- **左下角模型名消失**：代码块折叠使用清除到屏幕底部的 ANSI 指令，擦掉 JLine 状态栏，而增量绘制没有恢复不变字段。已改成限定行范围清理，并补充终端模拟回放；133 项 TUI 测试通过。
- **`Failed to read prompt resource: base.md`**：助手在用户进程运行期间重新打包并替换正在使用的 JAR。旧进程启动于 10:54:39，产物更新于 10:58:46；新进程能加载全部 18 种提示词组合。诊断为运行中产物被改写导致资源读取异常，未取得当时 IOException 的完整底层堆栈。后续改为临时目录构建、通过文件替换发布，并验证旧打开文件仍可读取；原始 Maven 打包流程本身尚未因此全面改造。
- **内置 Skill 的 tags 警告**：内置 `better-harness` 用多行 YAML 列表，极简解析器只支持行内数组。已统一为行内格式、升级内置缓存版本、修正本机缓存，33 项 Skill 测试通过。没有为此扩展完整 YAML 解析能力。

本轮累计代码仍未提交。上述其他故障的测试成绩也不能用于证明待评审的意图判断升级方案有效。

## 11. 评审结论与落地（Claude，2026-09-25）

**结论：附条件接受方案方向，拒绝两次局部修复。** 已按下述方案实施，替代第 7 节提议。

归因核实：原始账本 `session-1790305232921-…` 中 DeepSeek 输出的确为 `｜DSML｜｜ calls` 容器；11:12:50 的请求日志为 `tools=0`。归因成立。

根本问题是门禁默认值方向错误：`looksActionable()` 没命中动作词表就清空全部工具，每次漏判都是一次“模型想做事却没有工具”的静默失败。自然语言判断可以用，但只能用来收紧（命中才限制），不能用来授予基本能力（没命中就剥夺）。

对第 9 节问题的回答：

1. 归因充分；证据缺口（真实服务端控制变量）不影响修复方向，因为修复后提供 tools 本来就是正确请求形态。
2. 正则不再作为工具资格门槛。保留的只有两类只做收紧的高精度判断：明确禁网，明确标题标记（`《…》`、`# `、“（附…面试题）”、“X：Y？”）。
3. 续接编号与分句补丁属于补丁堆积，已撤回；DSML `calls` 容器兼容属于协议格式解析，有真实证据，保留。
4. 是降级，但只对“没有标记的裸标题”这一窄场景：从代码拦截变为提示词要求澄清。URL 来源校验不变，模型仍不能抓取臆造 URL；未经请求的 `web_search` 是只读操作，结果仍包成不可信数据。可以接受。
5. 原门禁从来不是副作用防线（输入含“修改”即开放全部写工具）。真正的防线已补：交互式 CLI 默认确认 `execute_command`、`revert_turn` 与全部 MCP 工具。
6. 不需要额外意图分类请求。
7. 未转换的 DSML 现在会提示“工具调用未执行”，并注明若是用户要求展示的示例可忽略。
8. 确定性测试只验证工具暴露、转换、执行分发与审批分支；模型是否会澄清裸标题属于模型行为，需要单独的真实模型样本，本次未运行。
9. 见下方落地清单。

落地清单（均有离线测试，未调用真实模型）：

- `TurnToolPolicy`：删除 `looksActionable` 及其动作词表，`actionable` 换成只收紧的 `headlineOnly`；撤回 `fromConversationReply` 与分句判断。测试 `TurnToolPolicyTest.onlyHighPrecisionHeadlineMarkersHideWebSearch`、`headlinesNeverLoseLocalToolsOrGainUngroundedFetch`
- `Agent`：撤回续接状态；标题输入时在终端提示“本轮未开放联网工具”
- `LlmClient.looksLikeUnexecutedToolCall` + `DeepSeekClient` 实现，三条执行路径在最终回复后提示。测试 `UnexecutedToolCallNoticeTest`、`DeepSeekUnexecutedToolCallTest`
- HITL 三档：`/hitl default`（启动默认）、`/hitl on`、`/hitl off`；默认档只在交互式 `SwitchableHitlHandler` 开启，评测等非交互处理器保持关闭。测试 `HitlDefaultConfirmationTest`
- 事故回放 `DeepSeekConversationReplayTest` 在去掉全部自然语言补丁后仍通过：“1”与“进入 demo 目录，编译并运行 Hello.java”都能拿到工具并执行
- 顺带：删除误提交的仓库根目录 `Hello.java`；状态栏里并不存在的 Ctrl+Y 提示改为 `/hitl` 命令
