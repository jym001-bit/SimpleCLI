# Release manifest

根据 `input/VERSION` 和 `input/artifacts.tsv` 生成 `dist/release-manifest.txt`。

格式：

```text
release=<VERSION>
artifact=<name> sha256=<sha256>
artifact=<name> sha256=<sha256>
count=<artifact count>
```

要求：

- artifact 行按 name 字典序排列；
- 不包含表头、空行或额外空格；
- 文件以换行结尾；
- 不修改 `input` 下的文件。
