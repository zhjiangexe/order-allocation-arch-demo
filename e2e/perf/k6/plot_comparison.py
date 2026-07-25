#!/usr/bin/env python3
"""v1（樂觀鎖）vs v3（SKU 分區 single-writer）壓測對比圖。

讀兩份 k6 --summary-export 的 JSON（拿吞吐量），加上命令列傳入的衝突計數
（跑完 ./e2e/perf/run.sh verify <SKU> 之後手動抄過來的
order_allocation_retry_attempts_total / _exhausted_total），畫兩張長條圖：
吞吐量對比、衝突次數對比。

用法（下面的檔名與數字就是 e2e/perf/k6/results/ 裡實際跑出來、已 commit 的結果，
可以直接照抄執行）：
  python3 plot_comparison.py \
    --v1-summary hot-sku-burst-orderid-key.json --v1-attempts 15 --v1-exhausted 6 \
    --v3-summary hot-sku-burst-sku-key.json     --v3-attempts 0  --v3-exhausted 0
"""

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt


def load_iterations_rate(summary_path: str) -> float:
    """從 k6 summary JSON 讀出 iterations.rate（每秒完成幾張訂單）。"""
    with open(summary_path, encoding="utf-8") as f:
        data = json.load(f)
    return data["metrics"]["iterations"]["rate"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-summary", required=True, help="v1（orderId 分區）的 k6 summary JSON 路徑")
    parser.add_argument("--v1-attempts", required=True, type=int, help="v1 的 order_allocation_retry_attempts_total")
    parser.add_argument("--v1-exhausted", required=True, type=int, help="v1 的 order_allocation_retry_exhausted_total")
    parser.add_argument("--v3-summary", required=True, help="v3（sku 分區）的 k6 summary JSON 路徑")
    parser.add_argument("--v3-attempts", required=True, type=int, help="v3 的 order_allocation_retry_attempts_total")
    parser.add_argument("--v3-exhausted", required=True, type=int, help="v3 的 order_allocation_retry_exhausted_total")
    parser.add_argument(
        "--output-dir",
        default=".",
        help="輸出 PNG 的資料夾，預設當前目錄",
    )
    return parser.parse_args()


def plot_throughput(v1_rate: float, v3_rate: float, output_dir: Path) -> None:
    """畫吞吐量（iterations/s，也就是每秒完成幾張訂單）對比長條圖。"""
    fig, ax = plt.subplots(figsize=(6, 4))
    labels = ["v1（orderId 分區）", "v3（sku 分區 single-writer）"]
    values = [v1_rate, v3_rate]
    bars = ax.bar(labels, values, color=["#4C72B0", "#55A868"])
    ax.set_ylabel("吞吐量（訂單／秒）")
    ax.set_title("v1 vs v3 吞吐量對比")
    for bar, value in zip(bars, values):
        ax.annotate(
            f"{value:.1f}",
            xy=(bar.get_x() + bar.get_width() / 2, bar.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
        )
    fig.tight_layout()
    fig.savefig(output_dir / "throughput_comparison.png", dpi=150)
    plt.close(fig)


def plot_conflicts(
    v1_attempts: int, v1_exhausted: int, v3_attempts: int, v3_exhausted: int, output_dir: Path
) -> None:
    """畫衝突次數（重試次數／重試用盡次數）對比長條圖。"""
    fig, ax = plt.subplots(figsize=(6, 4))
    labels = ["v1（orderId 分區）", "v3（sku 分區 single-writer）"]
    attempts = [v1_attempts, v3_attempts]
    exhausted = [v1_exhausted, v3_exhausted]
    x = range(len(labels))
    width = 0.35
    ax.bar([i - width / 2 for i in x], attempts, width, label="重試次數", color="#C44E52")
    ax.bar([i + width / 2 for i in x], exhausted, width, label="重試用盡次數", color="#8172B2")
    ax.set_xticks(list(x))
    ax.set_xticklabels(labels)
    ax.set_ylabel("次數")
    ax.set_title("v1 vs v3 衝突率對比")
    ax.legend()
    fig.tight_layout()
    fig.savefig(output_dir / "conflict_comparison.png", dpi=150)
    plt.close(fig)


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    v1_rate = load_iterations_rate(args.v1_summary)
    v3_rate = load_iterations_rate(args.v3_summary)
    plot_throughput(v1_rate, v3_rate, output_dir)
    plot_conflicts(args.v1_attempts, args.v1_exhausted, args.v3_attempts, args.v3_exhausted, output_dir)

    print(f"已輸出 {output_dir / 'throughput_comparison.png'}")
    print(f"已輸出 {output_dir / 'conflict_comparison.png'}")


if __name__ == "__main__":
    main()
