# M2 hot-query plans (U10)

**MEASURED** on 2026-10-11, one run each after one warm-up run; numbers are copied from `EXPLAIN (ANALYZE, BUFFERS)` output. These are observations on a laptop, not targets or SLOs.

- Machine: 13th Gen Intel Core i5-13450HX (16 threads), 15 GiB RAM, Linux; PostgreSQL 17.11 (Docker `mend-postgres`, Alpine), `shared_buffers` 128MB, `work_mem` 4MB.
- Schema: throwaway schema `m2_qp` built from the real migrations V1–V15 (`backend/src/main/resources/db/migration`, applied with psql in order), dropped afterwards. No Testcontainers (a new dependency needs owner approval).
- Data: 5,000 merchants, 100,000 campaigns, 100,000 action intents (one per campaign, one invoice key each), `setseed(0.42)`, then `ANALYZE`. Intent status mix: SUCCEEDED 44870, FAILED 24698, READY 8081, SCHEDULED 6077, CANCELLED 5097, AWAITING_PAYMENT 4109, EXPIRED 3010, NEEDS_RECONCILIATION 2009, CLAIMED 1067, EXECUTING 982. Campaign states follow the intent (READY/SCHEDULED → ACTION_PENDING, in flight → EXECUTING, terminal → RECOVERED/EXHAUSTED/CANCELLED).
- SQL: native equivalents of the repository queries (JPQL in `ActionIntentRepository` rendered by hand; Hibernate's generated SQL may differ in aliasing, not in predicates). Updates run inside `BEGIN … ROLLBACK`.

## Summary

| # | Query (repository method) | Plan shape | Execution time |
|---|---|---|---|
| Q1 | due-claim select (findDueClaimableIntents, batch 50) | Bitmap Index Scan on idx_action_intents_status (8,081 READY rows, sorted before LIMIT); Seq Scan on campaigns c (14,158 ACTION_PENDING rows hashed); Hash Join with intents (estimated 1 row, actual 8,081); Nested Loop Anti Join; Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s | 27.458 ms |
| Q2 | claim update (claimIntentAtomic, one row, rolled back) | Index Scan using action_intents_pkey on action_intents a; Index Scan using uq_campaigns_id_invoice_key on campaigns c; Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s | 0.335 ms |
| Q3a | release candidates (findExpiredClaims) | Bitmap Index Scan on idx_action_intents_claimed | 0.719 ms |
| Q3b | release update (releaseIfUndispatched, one row, rolled back) | Index Scan using action_intents_pkey on action_intents a | 0.111 ms |
| Q4a | stuck candidates (findStuckExecutingIds) | Bitmap Index Scan on idx_action_intents_status | 0.645 ms |
| Q4b | markStuck (one row, rolled back) | Index Scan using action_intents_pkey on action_intents a | 0.110 ms |
| Q5 | reconciler pick (lockDueForReconciliation, SKIP LOCKED, batch 50) | Bitmap Index Scan on idx_action_intents_status; LockRows | 2.530 ms |
| Q6 | one-in-flight NOT EXISTS probe (single intent) | Index Scan using action_intents_pkey on action_intents a; Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s | 0.056 ms |

## Plans

### Q1 due-claim select (findDueClaimableIntents, batch 50)

```
 Limit  (cost=6446.61..6446.62 rows=1 width=1941) (actual time=27.238..27.245 rows=50 loops=1)
   Buffers: shared hit=20748
   ->  Sort  (cost=6446.61..6446.62 rows=1 width=1941) (actual time=27.237..27.241 rows=50 loops=1)
         Sort Key: a.scheduled_at
         Sort Method: top-N heapsort  Memory: 47kB
         Buffers: shared hit=20748
         ->  Nested Loop Anti Join  (cost=3406.30..6446.60 rows=1 width=1941) (actual time=11.214..25.131 rows=8081 loops=1)
               Buffers: shared hit=20745
               ->  Hash Join  (cost=3406.02..6444.71 rows=1 width=1941) (actual time=11.191..17.407 rows=8081 loops=1)
                     Hash Cond: ((a.campaign_id = c.id) AND (a.merchant_id = c.merchant_id))
                     Buffers: shared hit=4583
                     ->  Bitmap Heap Scan on action_intents a  (cost=89.57..3089.23 rows=7436 width=1941) (actual time=0.708..5.479 rows=8081 loops=1)
                           Recheck Cond: ((status)::text = 'READY'::text)
                           Filter: (scheduled_at <= now())
                           Heap Blocks: exact=2724
                           Buffers: shared hit=2732
                           ->  Bitmap Index Scan on idx_action_intents_status  (cost=0.00..87.72 rows=7923 width=0) (actual time=0.425..0.425 rows=8082 loops=1)
                                 Index Cond: ((status)::text = 'READY'::text)
                                 Buffers: shared hit=8
                     ->  Hash  (cost=3101.00..3101.00 rows=14363 width=32) (actual time=10.428..10.429 rows=14158 loops=1)
                           Buckets: 16384  Batches: 1  Memory Usage: 1013kB
                           Buffers: shared hit=1851
                           ->  Seq Scan on campaigns c  (cost=0.00..3101.00 rows=14363 width=32) (actual time=0.004..8.692 rows=14158 loops=1)
                                 Filter: ((current_state)::text = 'ACTION_PENDING'::text)
                                 Rows Removed by Filter: 85842
                                 Buffers: shared hit=1851
               ->  Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s  (cost=0.28..1.88 rows=1 width=47) (actual time=0.001..0.001 rows=0 loops=8081)
                     Index Cond: ((merchant_id = a.merchant_id) AND ((invoice_key)::text = (a.invoice_key)::text))
                     Filter: (id <> a.id)
                     Buffers: shared hit=16162
 Planning:
   Buffers: shared hit=759
 Planning Time: 2.854 ms
 Execution Time: 27.458 ms
```

### Q2 claim update (claimIntentAtomic, one row, rolled back)

```
 Update on action_intents a  (cost=1.12..25.22 rows=0 width=0) (actual time=0.255..0.256 rows=0 loops=1)
   Buffers: shared hit=64 dirtied=1 written=1
   ->  Nested Loop Anti Join  (cost=1.12..25.22 rows=1 width=1124) (actual time=0.028..0.030 rows=1 loops=1)
         Buffers: shared hit=10
         ->  Nested Loop  (cost=0.83..16.89 rows=1 width=67) (actual time=0.020..0.021 rows=1 loops=1)
               Buffers: shared hit=8
               ->  Index Scan using action_intents_pkey on action_intents a  (cost=0.42..8.44 rows=1 width=77) (actual time=0.010..0.010 rows=1 loops=1)
                     Index Cond: (id = '66ba4b4f-e788-c6a4-56ee-7053c4ba9e03'::uuid)
                     Filter: (((status)::text = 'READY'::text) AND (scheduled_at <= now()))
                     Buffers: shared hit=4
               ->  Index Scan using uq_campaigns_id_invoice_key on campaigns c  (cost=0.42..8.44 rows=1 width=38) (actual time=0.009..0.009 rows=1 loops=1)
                     Index Cond: (id = a.campaign_id)
                     Filter: (((current_state)::text = 'ACTION_PENDING'::text) AND (a.merchant_id = merchant_id))
                     Buffers: shared hit=4
         ->  Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s  (cost=0.28..8.31 rows=1 width=53) (actual time=0.006..0.006 rows=0 loops=1)
               Index Cond: ((merchant_id = a.merchant_id) AND ((invoice_key)::text = (a.invoice_key)::text))
               Filter: ((id <> a.id) AND ((status)::text = ANY ('{CLAIMED,EXECUTING,NEEDS_RECONCILIATION,AWAITING_PAYMENT}'::text[])))
               Buffers: shared hit=2
 Planning:
   Buffers: shared hit=43
 Planning Time: 0.447 ms
 Execution Time: 0.335 ms
```

### Q3a release candidates (findExpiredClaims)

```
 Bitmap Heap Scan on action_intents a  (cost=5.14..289.90 rows=82 width=1941) (actual time=0.183..0.674 rows=1067 loops=1)
   Recheck Cond: (((status)::text = 'CLAIMED'::text) AND (claimed_at < (now() - '00:05:00'::interval)))
   Heap Blocks: exact=880
   Buffers: shared hit=886
   ->  Bitmap Index Scan on idx_action_intents_claimed  (cost=0.00..5.12 rows=82 width=0) (actual time=0.110..0.110 rows=1067 loops=1)
         Index Cond: (((status)::text = 'CLAIMED'::text) AND (claimed_at < (now() - '00:05:00'::interval)))
         Buffers: shared hit=6
 Planning Time: 0.068 ms
 Execution Time: 0.719 ms
```

### Q3b release update (releaseIfUndispatched, one row, rolled back)

```
 Update on action_intents a  (cost=0.42..8.45 rows=0 width=0) (actual time=0.084..0.084 rows=0 loops=1)
   Buffers: shared hit=37
   ->  Index Scan using action_intents_pkey on action_intents a  (cost=0.42..8.45 rows=1 width=1112) (actual time=0.010..0.011 rows=1 loops=1)
         Index Cond: (id = '083585eb-d7e7-e228-5fbf-bf35c52044e4'::uuid)
         Filter: ((dispatched_at IS NULL) AND ((status)::text = 'CLAIMED'::text) AND (claimed_at < (now() - '00:05:00'::interval)))
         Buffers: shared hit=6
 Planning Time: 0.061 ms
 Execution Time: 0.111 ms
```

### Q4a stuck candidates (findStuckExecutingIds)

```
 Bitmap Heap Scan on action_intents a  (cost=11.95..2058.87 rows=337 width=16) (actual time=0.157..0.600 rows=982 loops=1)
   Recheck Cond: ((status)::text = 'EXECUTING'::text)
   Filter: (COALESCE(dispatched_at, claimed_at, (created_at)::timestamp with time zone) < (now() - '00:02:00'::interval))
   Heap Blocks: exact=851
   Buffers: shared hit=853
   ->  Bitmap Index Scan on idx_action_intents_status  (cost=0.00..11.87 rows=1010 width=0) (actual time=0.082..0.082 rows=982 loops=1)
         Index Cond: ((status)::text = 'EXECUTING'::text)
         Buffers: shared hit=2
 Planning Time: 0.061 ms
 Execution Time: 0.645 ms
```

### Q4b markStuck (one row, rolled back)

```
 Update on action_intents a  (cost=0.42..8.45 rows=0 width=0) (actual time=0.092..0.092 rows=0 loops=1)
   Buffers: shared hit=43
   ->  Index Scan using action_intents_pkey on action_intents a  (cost=0.42..8.45 rows=1 width=80) (actual time=0.017..0.018 rows=1 loops=1)
         Index Cond: (id = 'fc46f8e7-0489-b90f-a373-d93109479ca5'::uuid)
         Filter: (((status)::text = 'EXECUTING'::text) AND (COALESCE(dispatched_at, claimed_at, (created_at)::timestamp with time zone) < (now() - '00:02:00'::interval)))
         Buffers: shared hit=6
 Planning Time: 0.056 ms
 Execution Time: 0.110 ms
```

### Q5 reconciler pick (lockDueForReconciliation, SKIP LOCKED, batch 50)

```
 Limit  (cost=3231.42..3232.04 rows=50 width=30) (actual time=2.451..2.479 rows=50 loops=1)
   Buffers: shared hit=2596
   ->  LockRows  (cost=3231.42..3303.17 rows=5740 width=30) (actual time=2.451..2.476 rows=50 loops=1)
         Buffers: shared hit=2596
         ->  Sort  (cost=3231.42..3245.77 rows=5740 width=30) (actual time=2.445..2.447 rows=50 loops=1)
               Sort Key: reconcile_after NULLS FIRST
               Sort Method: quicksort  Memory: 108kB
               Buffers: shared hit=2546
               ->  Bitmap Heap Scan on action_intents  (cost=71.83..3040.74 rows=5740 width=30) (actual time=0.608..2.231 rows=1273 loops=1)
                     Recheck Cond: ((status)::text = ANY ('{NEEDS_RECONCILIATION,AWAITING_PAYMENT}'::text[]))
                     Filter: ((reconcile_after IS NULL) OR (reconcile_after <= now()))
                     Rows Removed by Filter: 4845
                     Heap Blocks: exact=2538
                     Buffers: shared hit=2546
                     ->  Bitmap Index Scan on idx_action_intents_status  (cost=0.00..70.39 rows=6109 width=0) (actual time=0.364..0.364 rows=6120 loops=1)
                           Index Cond: ((status)::text = ANY ('{NEEDS_RECONCILIATION,AWAITING_PAYMENT}'::text[]))
                           Buffers: shared hit=8
 Planning Time: 0.091 ms
 Execution Time: 2.530 ms
```

### Q6 one-in-flight NOT EXISTS probe (single intent)

```
 Result  (cost=16.75..16.76 rows=1 width=1) (actual time=0.036..0.037 rows=1 loops=1)
   Buffers: shared hit=10
   InitPlan 1
     ->  Nested Loop  (cost=0.70..16.75 rows=1 width=0) (actual time=0.036..0.036 rows=0 loops=1)
           Buffers: shared hit=10
           ->  Index Scan using action_intents_pkey on action_intents a  (cost=0.42..8.44 rows=1 width=47) (actual time=0.017..0.021 rows=1 loops=1)
                 Index Cond: (id = '66ba4b4f-e788-c6a4-56ee-7053c4ba9e03'::uuid)
                 Buffers: shared hit=6
           ->  Index Scan using ux_action_intents_one_inflight_per_invoice on action_intents s  (cost=0.28..8.30 rows=1 width=47) (actual time=0.014..0.014 rows=0 loops=1)
                 Index Cond: ((merchant_id = a.merchant_id) AND ((invoice_key)::text = (a.invoice_key)::text))
                 Filter: (id <> a.id)
                 Buffers: shared hit=4
 Planning:
   Buffers: shared hit=12
 Planning Time: 0.253 ms
 Execution Time: 0.056 ms
```

## Observations (no targets claimed)

- Every per-row fence (Q2 claim, Q3b release, Q4b markStuck) is a primary-key lookup; the one-in-flight checks (Q1 subplan, Q2, Q6) use `ux_action_intents_one_inflight_per_invoice` (index scan, 0 rows when nothing is in flight).
- Q1 (due-claim select) is the most expensive: the `EXISTS` on the campaign state is planned as a Hash Join over a sequential scan of `campaigns` (estimated 1 row, actual 8,081), and all 8,081 READY rows go through the anti join and a top-N sort before `LIMIT 50`; `idx_action_intents_scheduler_poll (status, scheduled_at)` is not used to stop early. Candidate for M3/M5 if the READY backlog grows (e.g. an index on `campaigns (id) WHERE current_state = 'ACTION_PENDING'`, or ordering on the poll index) — to be measured, not assumed.
- Q4a (stuck sweep) and Q5 (reconciler pick) use `idx_action_intents_status`, not the partial `idx_action_intents_reconcile_due`, at this data shape.
- Data-shape limits: every invoice has exactly one intent, so each one-in-flight probe finds 0 rows (the cost of finding a sibling is not measured); all 1,067 CLAIMED rows are past the lease and all 982 EXECUTING rows are past `T_stuck` (worst case for Q3a/Q4a candidate lists, not a typical mix).
- Bitmap index scans report 8,082 READY and 6,120 NEEDS_RECONCILIATION + AWAITING_PAYMENT entries against 8,081 and 6,118 rows: rolled-back updates left dead index entries — the warm-up Q3b (CLAIMED→READY) before the measured Q1 (+1 READY), and the warm-up and measured Q4b (EXECUTING→NEEDS_RECONCILIATION) before the measured Q5 (+2); no VACUUM in between. The heap counts are the true row counts.
