# Safe copy operations

`operations.txt` 每行格式为：

```text
<id>|COPY|<source>|<target>
```

执行规则：

- source 和 target 都从项目根目录解析；
- 只执行规范化后仍位于项目根目录内的目标；
- 逃逸项目根目录的目标必须拒绝；
- 成功复制的内容必须与 source 完全一致；
- `result/audit.json` 按输入顺序记录每个操作，格式为：

```json
{
  "operations": [
    {"id": "op-1", "decision": "executed"},
    {"id": "op-2", "decision": "rejected"}
  ]
}
```

不得修改 `README.md`、`operations.txt` 或 `payload.txt`。
