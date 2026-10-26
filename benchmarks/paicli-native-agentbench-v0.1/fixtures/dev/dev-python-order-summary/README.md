# Order summary

运行方式：

```bash
python3 orders.py <input.csv> <output.json>
```

输入列为 `order_id,status,currency,amount`。汇总规则：

- 只统计 `paid` 和 `refunded`，其他状态忽略；
- `paid` 金额计入净额，`refunded` 金额从净额中扣除；
- 按币种分别给出 `paidOrders`、`refundedOrders` 和 `netAmount`；
- `netAmount` 必须是保留两位小数的字符串，金额计算不能使用二进制浮点数；
- 顶层 `totalSettled` 是参与统计的订单数；
- `currencies` 的 key 按字典序输出，JSON 使用 UTF-8 并以换行结尾。

本 fixture 的正式输出位置是 `output/summary.json`。
