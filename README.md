This is an attempt to learning the search over a curated set of places. Kind of a small version of google maps.

## Overview

LocationSearch pulls named points of interest (POIs) from [Overture Maps](https://overturemaps.org/) and [OpenStreetMap](https://www.openstreetmap.org/) across a set of city bounding boxes (currently New York City, Washington D.C., and San Francisco), deduplicates and merges records that represent the same real-world place across sources, and builds an in-memory trie-based prefix-search index over the result. Full place details are hydrated from Redis. Two gRPC APIs are exposed on top of this:

1. **Prefix search** — given a prefix, a user location, and a requested top-k, returns the top-k places ranked by a blend of a learned behavioral score and proximity to the user.
2. **Place details** — given a place id, returns the full place record.

Search and selection events are streamed back through Kafka, aggregated over a rolling window, and used to rebuild the ranking so it converges on what users actually pick (see **Learning loop** below).

## Pipeline

- `BuildCityDatasetOvertureImpl` / `BuildCityDatasetOsmImpl` — query Overture's Parquet-over-S3 dataset and local OSM PBF extracts (via DuckDB) for named POIs within a bounding box.
- `ConvergeDatasetService` — deduplicates POIs representing the same place across sources (grid-based spatial blocking + Jaro-Winkler name similarity + union-find clustering) and fuses each duplicate group into a single `MergedPoi`, including a `seedScore` popularity proxy derived from source confidence, source count, and weak notability signals (Wikipedia/Wikidata tags, web presence, destination-category bonus).
- `PoiDatasetStartup` — runs the pipeline across all configured city bounding boxes and writes the merged dataset to `src/main/resources/merged-pois.json`.
- `TrieBuilder` — builds an in-memory trie over normalized place names and aliases, keeping the top places at every prefix node. `build()` ranks by `seedScore`; `buildWithMetrics(...)` blends behavioral metrics (popularity, selection rate, MRR, effort, abandonment) into a per-(prefix, place) score and stores it on each node.
- `RedisClient` / `scripts/load_pois_into_redis.py` — hydration store for full `MergedPoi` records, keyed by place id.
- `PlaceSearchServer` / `PlaceSearchServiceImpl` — gRPC server exposing the prefix-search and place-details APIs. It serves a flat prefix-keyed ranking index (`prefix -> ScoredPlace`) held by `RankingIndexProvider`, ranking candidates with `PlaceRanker.blend` (learned behavioral score blended with haversine → exponential-decay proximity closeness).

## Learning loop

The system learns from usage and hot-swaps the improved ranking into the serving JVM without a restart:

- `SearchEventProducer` — the serving path emits prefix-search and place-selection events to Kafka.
- `S3EventService` / event consumers — events are landed in S3 (MinIO locally).
- `RankMetricCollectorJob` (in `BatchJobApplication`) — daily, aggregates raw events into per-day metric deltas in S3.
- `TrieRebuildJob` — daily, aggregates the last 30 days of metric deltas, rebuilds the trie with `buildWithMetrics(...)`, serializes it to S3 as sharded NDJSON under `trie-snapshot/<date>/` (via `TrieSnapshot`), and writes the `trie-snapshot/latest` pointer **last** so readers never observe a partial snapshot.
- `RankingIndexProvider` — in the serving JVM, polls `trie-snapshot/latest` every 5 minutes and atomically hot-swaps the ranking index when a newer snapshot appears. It bootstraps from a seed-only build and builds its S3 client lazily, so object storage being unavailable at startup never crashes serving.

## Running locally

```
docker-compose up -d                                    # start Redis, Kafka, and MinIO (S3)
mvn -q compile                                           # generates gRPC/protobuf stubs
java -cp target/classes:<deps> org.learning.setup.PoiDatasetStartup   # (re)build merged-pois.json
python3 scripts/load_pois_into_redis.py                  # load dataset into Redis
java -cp target/classes:<deps> org.learning.grpc.PlaceSearchServer    # start the gRPC server on :50051
java -cp target/classes:<deps> org.learning.batch.BatchJobApplication # run metric-collection + trie-rebuild jobs
```
