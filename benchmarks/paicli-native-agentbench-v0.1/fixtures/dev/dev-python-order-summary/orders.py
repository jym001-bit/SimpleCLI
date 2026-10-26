#!/usr/bin/env python3
import csv
import json
import sys
from pathlib import Path


def summarize(input_path: Path) -> dict:
    currencies: dict[str, dict[str, object]] = {}
    total = 0
    with input_path.open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            if row["status"] == "cancelled":
                continue
            bucket = currencies.setdefault(
                row["currency"],
                {"paidOrders": 0, "refundedOrders": 0, "netAmount": 0.0},
            )
            total += 1
            if row["status"] == "paid":
                bucket["paidOrders"] += 1
            elif row["status"] == "refunded":
                bucket["refundedOrders"] += 1
            bucket["netAmount"] += float(row["amount"])

    for bucket in currencies.values():
        bucket["netAmount"] = f"{bucket['netAmount']:.2f}"
    return {"totalSettled": total, "currencies": currencies}


def main() -> None:
    input_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(summarize(input_path), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
