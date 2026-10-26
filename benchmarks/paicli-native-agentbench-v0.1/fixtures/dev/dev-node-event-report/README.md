# Event report

运行方式：

```bash
node events.js <input.ndjson> <output.json>
```

每一行是一个包含 `type`、`user`、`active` 的 JSON 对象。汇总规则：

- 只统计 `active` 严格等于 `true` 的事件；
- `totalActive` 是有效事件总数；
- `byType` 按事件类型给出 `events` 和 `uniqueUsers`；
- 同一用户的多次同类事件全部计入 `events`，但在 `uniqueUsers` 中只计一次；
- 类型 key 按字典序输出，文件使用两个空格缩进并以换行结尾。

本 fixture 的正式输出位置是 `output/report.json`。
