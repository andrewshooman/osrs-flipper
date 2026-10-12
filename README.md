# Market data backup (machine-written)

Whole-market snapshots from the OSRS Wiki real-time prices API, one parquet
file per UTC day (`ts, id, ah, hv, al, lv`), as written by `research/collect.py`.
Restore with: copy `1h/` and `5m/` into `$OSF_DATA` (default `/home/user/osrs-data`).
The data is public and can always be re-downloaded; this branch just saves hours.
