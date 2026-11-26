"""Load test: synchronous REST -> SOAP call versus REST -> IBM MQ -> SOAP with retries.

Both phases run against the same flaky ERP mock:
  - failure_rate:    share of ERP calls that fail before saving (ERP down, lock timeout)
  - lost_reply_rate: share of calls where the ERP saves the order but the reply is lost
  - latency_ms:      processing time of every ERP call

Writes results/summary.json. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import platform
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def call(method: str, url: str, body: dict | None = None, timeout: float = 30.0) -> tuple[int, dict, float]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
    elapsed = (time.perf_counter() - t0) * 1000
    try:
        parsed = json.loads(payload) if payload else {}
    except json.JSONDecodeError:
        parsed = {}
    return status, parsed, elapsed


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round(p / 100 * len(ordered) + 0.5)) - 1))
    return ordered[k]


def make_orders(n: int, invalid_every: int | None) -> list[dict]:
    orders = []
    for i in range(n):
        blocked = invalid_every is not None and i % invalid_every == invalid_every - 1
        orders.append({
            "customerCode": f"BLOCKED-{i:05d}" if blocked else f"C{i % 5000:05d}",
            "totalAmount": round(10 + (i * 37 % 5000) / 10, 2),
            "lineCount": 1 + i % 7,
        })
    return orders


def wait_until(predicate, timeout_s: float, interval_s: float = 1.0):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(interval_s)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://localhost:8080")
    ap.add_argument("--erp", default="http://localhost:8081")
    ap.add_argument("--sync-orders", type=int, default=500)
    ap.add_argument("--async-orders", type=int, default=2000)
    ap.add_argument("--invalid-every", type=int, default=20, help="every Nth async order uses a blocked customer")
    ap.add_argument("--concurrency", type=int, default=16)
    ap.add_argument("--failure-rate", type=float, default=0.20)
    ap.add_argument("--lost-reply-rate", type=float, default=0.05)
    ap.add_argument("--latency-ms", type=int, default=50)
    args = ap.parse_args()

    fault = {"failureRate": args.failure_rate, "lostReplyRate": args.lost_reply_rate, "latencyMs": args.latency_ms}

    # ------------------------------------------------------------------ phase A: synchronous baseline
    print("Phase A: synchronous REST -> SOAP, no retries")
    call("POST", f"{args.erp}/erp/reset", {})
    call("POST", f"{args.erp}/erp/config", fault)
    call("POST", f"{args.gateway}/orders/reset", {})
    sync_orders = make_orders(args.sync_orders, None)
    t0 = time.perf_counter()
    with ThreadPoolExecutor(args.concurrency) as pool:
        sync_results = list(pool.map(lambda o: call("POST", f"{args.gateway}/orders/sync", o), sync_orders))
    sync_wall_ms = (time.perf_counter() - t0) * 1000
    sync_latency = [r[2] for r in sync_results]
    sync_ok = sum(1 for r in sync_results if r[0] == 200)
    sync_failed = sum(1 for r in sync_results if r[0] == 502)
    erp_after_sync = call("GET", f"{args.erp}/erp/stats")[1]
    saved_but_reported_failed = erp_after_sync["uniqueOrders"] - sync_ok
    print(f"  ok={sync_ok} failed={sync_failed} p95={percentile(sync_latency, 95):.0f} ms "
          f"saved-but-reported-failed={saved_but_reported_failed}")

    # ------------------------------------------------------------------ phase B: IBM MQ + retries
    print("Phase B: REST -> IBM MQ -> SOAP with retries, idempotency and dead-letter queue")
    call("POST", f"{args.erp}/erp/reset", {})
    call("POST", f"{args.erp}/erp/config", fault)
    call("POST", f"{args.gateway}/orders/reset", {})
    async_orders = make_orders(args.async_orders, args.invalid_every)
    invalid = sum(1 for o in async_orders if o["customerCode"].startswith("BLOCKED"))
    valid = len(async_orders) - invalid
    t0 = time.perf_counter()
    with ThreadPoolExecutor(args.concurrency) as pool:
        async_results = list(pool.map(lambda o: call("POST", f"{args.gateway}/orders", o), async_orders))
    submit_wall_ms = (time.perf_counter() - t0) * 1000
    accepted = sum(1 for r in async_results if r[0] == 202)
    async_latency = [r[2] for r in async_results]

    def done():
        stats = call("GET", f"{args.gateway}/orders/stats")[1]
        s = stats["byStatus"]
        finished = s["SENT"] + s["REJECTED_TO_DLQ"] + s["FAILED_TO_DLQ"]
        return stats if finished >= accepted else None

    gateway_stats = wait_until(done, timeout_s=900, interval_s=2)
    drained_wall_ms = (time.perf_counter() - t0) * 1000
    if gateway_stats is None:
        gateway_stats = call("GET", f"{args.gateway}/orders/stats")[1]
        print("  ! timeout waiting for the queue to drain")
    erp_after_async = call("GET", f"{args.erp}/erp/stats")[1]
    s = gateway_stats["byStatus"]
    lost = valid - s["SENT"]
    print(f"  accepted={accepted} sent={s['SENT']} dlq_rejected={s['REJECTED_TO_DLQ']} dlq_failed={s['FAILED_TO_DLQ']} "
          f"erp_unique={erp_after_async['uniqueOrders']} duplicates_absorbed={erp_after_async['duplicatesAbsorbed']}")

    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "environment": {
            "machine": __import__("os").environ.get("BENCH_ENV") or platform.platform(),
            "python": platform.python_version(),
        },
        "erp_faults": fault,
        "sync": {
            "orders": args.sync_orders,
            "ok": sync_ok,
            "failed_for_client": sync_failed,
            "lost_pct": round(100 * sync_failed / args.sync_orders, 2),
            "saved_but_reported_failed": saved_but_reported_failed,
            "api_ms_p50": round(percentile(sync_latency, 50), 1),
            "api_ms_p95": round(percentile(sync_latency, 95), 1),
            "api_ms_mean": round(statistics.mean(sync_latency), 1),
            "wall_ms": round(sync_wall_ms),
            "erp": erp_after_sync,
        },
        "async": {
            "orders": args.async_orders,
            "valid": valid,
            "invalid": invalid,
            "accepted": accepted,
            "sent": s["SENT"],
            "rejected_to_dlq": s["REJECTED_TO_DLQ"],
            "failed_to_dlq": s["FAILED_TO_DLQ"],
            "lost": lost,
            "lost_pct": round(100 * lost / valid, 2) if valid else 0.0,
            "erp_unique_orders": erp_after_async["uniqueOrders"],
            "duplicates_in_erp": erp_after_async["uniqueOrders"] - s["SENT"],
            "duplicate_attempts_absorbed": erp_after_async["duplicatesAbsorbed"],
            "erp_calls": erp_after_async["calls"],
            "erp_faults": erp_after_async["faultsBeforeSave"] + erp_after_async["faultsAfterSave"],
            "redeliveries": gateway_stats["redeliveries"],
            "api_ms_p50": round(percentile(async_latency, 50), 1),
            "api_ms_p95": round(percentile(async_latency, 95), 1),
            "api_ms_mean": round(statistics.mean(async_latency), 1),
            "end_to_end_ms_p50": gateway_stats["endToEndMsP50"],
            "end_to_end_ms_p95": gateway_stats["endToEndMsP95"],
            "submit_wall_ms": round(submit_wall_ms),
            "drained_wall_ms": round(drained_wall_ms),
            "throughput_orders_per_s": round(s["SENT"] / (gateway_stats["asyncWindowMs"] / 1000), 1)
            if gateway_stats["asyncWindowMs"] else 0.0,
            "gateway": gateway_stats,
            "erp": erp_after_async,
        },
    }
    out = ROOT / "results" / "summary.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Wrote {out.relative_to(ROOT)}")

    # The run fails if the integration lost or duplicated anything.
    ok = (lost == 0 and summary["async"]["duplicates_in_erp"] == 0
          and s["REJECTED_TO_DLQ"] == invalid and accepted == args.async_orders)
    print("CHECK:", "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
