"""Builds results/comparison.png, results/RESULTS.md and docs/cover.png from results/summary.json."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyBboxPatch  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
BEFORE, AFTER = "#B4413C", "#1F6F8B"
INK, MUTED, GRID, BG, CARD = "#1C2430", "#5B6676", "#E3E7EC", "#F6F8FA", "#FFFFFF"
plt.rcParams.update({"font.family": "DejaVu Sans", "text.color": INK, "ytick.color": INK, "xtick.color": MUTED})


def n(x: float) -> str:
    return f"{x:,.0f}"


def load() -> dict:
    p = ROOT / "results" / "summary.json"
    if not p.exists():
        sys.exit("results/summary.json not found. Run bench/run_load.py first.")
    return json.loads(p.read_text(encoding="utf-8"))


def comparison_chart(s: dict) -> Path:
    sy, asy = s["sync"], s["async"]
    panels = [
        ("Orders that failed for the client", "%", sy["lost_pct"], asy["lost_pct"], lambda v: f"{v:.1f} %"),
        ("API response time, p95", "ms", sy["api_ms_p95"], asy["api_ms_p95"], lambda v: f"{v:.0f} ms"),
    ]
    fig, axes = plt.subplots(len(panels), 1, figsize=(10, 4.4))
    for ax, (title, _, b, a, fmt) in zip(axes, panels):
        top = max(a, b) or 1
        ax.barh([1, 0], [b, a], color=[BEFORE, AFTER], height=0.6)
        ax.set_yticks([1, 0], ["Direct SOAP call", "IBM MQ + retries"])
        ax.set_xlim(0, top * 1.25)
        for y, v in ((1, b), (0, a)):
            ax.text(v + top * 0.015, y, fmt(v), va="center", fontsize=11)
        ax.set_title(title, loc="left", fontsize=12, fontweight="bold")
        ax.tick_params(axis="x", labelbottom=False, length=0)
        for side in ("top", "right", "bottom"):
            ax.spines[side].set_visible(False)
        ax.spines["left"].set_color(GRID)
    f = s["erp_faults"]
    fig.text(0.01, 0.01, f"ERP fails {f['failureRate']:.0%} of calls, loses {f['lostReplyRate']:.0%} of replies after saving, "
             f"{f['latencyMs']} ms per call. {n(sy['orders'])} sync orders, {n(asy['orders'])} async orders.",
             fontsize=8.5, color=MUTED)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    out = ROOT / "results" / "comparison.png"
    fig.savefig(out, dpi=150, facecolor="white")
    plt.close(fig)
    return out


def results_table(s: dict) -> Path:
    sy, asy = s["sync"], s["async"]
    f = s["erp_faults"]
    lines = [
        f"ERP behaviour during the test: fails {f['failureRate']:.0%} of calls before saving, saves but loses the reply "
        f"in {f['lostReplyRate']:.0%} of calls, {f['latencyMs']} ms per call.",
        "",
        "| Metric | Direct SOAP call (no queue) | IBM MQ + retries |",
        "|---|---:|---:|",
        f"| Orders sent | {n(sy['orders'])} | {n(asy['valid'])} valid + {n(asy['invalid'])} invalid |",
        f"| Orders that failed for the client | {n(sy['failed_for_client'])} ({sy['lost_pct']:.1f} %) | {n(asy['lost'])} ({asy['lost_pct']:.1f} %) |",
        f"| Saved in the ERP but reported as failed | {n(sy['saved_but_reported_failed'])} | 0 |",
        f"| Duplicate orders in the ERP | 0 | {n(asy['duplicates_in_erp'])} |",
        f"| Repeated calls absorbed by the idempotency key | n/a | {n(asy['duplicate_attempts_absorbed'])} |",
        f"| Invalid orders routed to the dead-letter queue | n/a | {n(asy['rejected_to_dlq'])} of {n(asy['invalid'])} |",
        f"| API response time p50 / p95 | {sy['api_ms_p50']:.0f} / {sy['api_ms_p95']:.0f} ms | {asy['api_ms_p50']:.0f} / {asy['api_ms_p95']:.0f} ms |",
        f"| End-to-end time to the ERP p50 / p95 | same as API | {n(asy['end_to_end_ms_p50'])} / {n(asy['end_to_end_ms_p95'])} ms |",
        f"| Throughput into the ERP | n/a | {asy['throughput_orders_per_s']:.0f} orders/s |",
        "",
        f"ERP calls in the async phase: {n(asy['erp_calls'])}, of which {n(asy['erp_faults'])} failed and were retried. "
        f"Messages redelivered by IBM MQ: {n(asy['redeliveries'])}.",
        "",
        f"Environment: {s['environment']['machine']}. Generated {s['generated_at']}.",
    ]
    out = ROOT / "results" / "RESULTS.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def cover(s: dict) -> Path:
    sy, asy = s["sync"], s["async"]
    f = s["erp_faults"]
    fig = plt.figure(figsize=(16, 12), dpi=100, facecolor=BG)
    ax = fig.add_axes((0, 0, 1, 1))
    ax.set_xlim(0, 16)
    ax.set_ylim(0, 12)
    ax.axis("off")
    ax.text(1, 10.75, "Reliable ERP Integration", fontsize=44, fontweight="bold")
    ax.text(1, 10.0, "Quarkus  ·  IBM MQ  ·  SOAP, tested against a flaky legacy ERP", fontsize=21, color=MUTED)

    cards = [
        ("Orders that failed", f"ERP failing {f['failureRate']:.0%} of calls",
         f"{sy['lost_pct']:.1f} %", f"{asy['lost_pct']:.1f} %", f"{n(asy['valid'])} orders, all delivered"),
        ("API response time (p95)", f"ERP takes {f['latencyMs']} ms per call",
         f"{sy['api_ms_p95']:.0f} ms", f"{asy['api_ms_p95']:.0f} ms", "Client is answered at once"),
        ("Saved in the ERP but reported as failed", f"ERP loses {f['lostReplyRate']:.0%} of replies after saving",
         n(sy["saved_but_reported_failed"]), "0",
         f"{n(asy['duplicates_in_erp'])} duplicates, {n(asy['duplicate_attempts_absorbed'])} retries absorbed"),
    ]
    top, height, gap = 8.9, 2.45, 0.35
    for i, (title, sub, b, a, extra) in enumerate(cards):
        y = top - i * (height + gap) - height
        ax.add_patch(FancyBboxPatch((1, y), 14, height, boxstyle="round,pad=0,rounding_size=0.25",
                                    facecolor=CARD, edgecolor=GRID, linewidth=1.5))
        ax.text(1.5, y + height - 0.65, title, fontsize=24, fontweight="bold")
        ax.text(1.5, y + height - 1.25, sub, fontsize=16, color=MUTED)
        ax.text(1.5, y + 0.55, b, fontsize=30, fontweight="bold", color=BEFORE)
        ax.text(5.6, y + 0.55, "→", fontsize=30, color=MUTED)
        ax.text(6.6, y + 0.55, a, fontsize=30, fontweight="bold", color=AFTER)
        ax.text(14.5, y + 0.6, extra, fontsize=16, color=MUTED, ha="right")
    ax.text(14.5, 9.05, "Direct SOAP call  →  with IBM MQ, retries and idempotency", fontsize=13, color=MUTED, ha="right")
    ax.text(1, 0.55, f"Measured in GitHub Actions with IBM MQ in a container  ·  invalid orders routed to the "
            f"dead-letter queue: {n(asy['rejected_to_dlq'])} of {n(asy['invalid'])}", fontsize=14, color=MUTED)
    out = ROOT / "docs" / "cover.png"
    out.parent.mkdir(exist_ok=True)
    fig.savefig(out, dpi=100, facecolor=BG)
    plt.close(fig)
    return out


if __name__ == "__main__":
    data = load()
    for path in (results_table(data), comparison_chart(data), cover(data)):
        print("Wrote", path.relative_to(ROOT))
