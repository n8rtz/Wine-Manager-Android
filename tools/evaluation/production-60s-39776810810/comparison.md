# Production 15s versus 60s shared budget

Same frozen 168-photo reference and production logic. Only the shared deadline constant is changed in the ephemeral CI build; normal app source remains at 15 seconds. Lab reruns are not used as the comparator.

| Budget | Correct | Wrong | Abstained | Unknown | All six correct / eligible | Deadlines | Median ms | p95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 15 seconds | 480 | 143 | 271 | 114 | 6 / 103 | 95 | 15231.0 | 17989 |
| 60 seconds | 486 | 139 | 269 | 114 | 6 / 103 | 1 | 16676.0 | 24218 |

The 114 unknown fields are not rewarded as correct blanks. References are single-agent visual review, not expert-adjudicated truth. This is a historical-run comparison, not randomized paired timing on the same runner. Emulator elapsed times are not phone performance; a phone waiting-time/battery check is still needed.

See comparison.json for per-field results, identity-deduplicated sensitivity, outcome transitions, input hashes, and the exact CI-only build modification.
