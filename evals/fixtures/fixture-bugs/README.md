# fixture-bugs

一个用于评测的最小 Java 工程，故意埋了几处缺陷，因此 `mvn test` 初始是失败的。

- `StringCalculator.add`：null / 空串 / 尾随逗号 / 负数处理有问题
- `Inventory.stockOf`：查询不存在的 SKU 会抛空指针
- `OrderParser.parsePrice`：缺少分隔符会数组越界，负价格没有校验

可分别运行：`mvn -o test -Dtest=StringCalculatorTest`、`-Dtest=InventoryTest`、`-Dtest=OrderParserTest`。
