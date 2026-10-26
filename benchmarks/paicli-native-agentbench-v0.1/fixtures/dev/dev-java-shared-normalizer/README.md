# Shared normalizer refactor

`CustomerLabeler` 和 `TicketLabeler` 的规范化语义必须保持一致：

- `null` 规范化为空字符串；
- 去掉首尾空白；
- 连续空白压缩为一个空格；
- 使用 `Locale.ROOT` 转为小写。

请消除两个现有类中的重复实现，改为复用 `dev.refactor` 包内的共享组件，同时保持它们当前的 public API。
