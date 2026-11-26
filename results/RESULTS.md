ERP behaviour during the test: fails 20% of calls before saving, saves but loses the reply in 5% of calls, 50 ms per call.

| Metric | Direct SOAP call (no queue) | IBM MQ + retries |
|---|---:|---:|
| Orders sent | 500 | 1,900 valid + 100 invalid |
| Orders that failed for the client | 111 (22.2 %) | 0 (0.0 %) |
| Saved in the ERP but reported as failed | 12 | 0 |
| Duplicate orders in the ERP | 0 | 0 |
| Repeated calls absorbed by the idempotency key | n/a | 97 |
| Invalid orders routed to the dead-letter queue | n/a | 100 of 100 |
| API response time p50 / p95 | 169 / 200 ms | 16 / 28 ms |
| End-to-end time to the ERP p50 / p95 | same as API | 12,712 / 24,022 ms |
| Throughput into the ERP | n/a | 68 orders/s |

ERP calls in the async phase: 2,577, of which 577 failed and were retried. Messages redelivered by IBM MQ: 0.

Environment: GitHub Actions ubuntu-latest runner, IBM MQ developer container, Java 21. Generated 2026-11-26T07:28:40+00:00.
