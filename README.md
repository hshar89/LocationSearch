This is an attempt to learning the search over a curated set of places. Kind of a small version of google maps.

## Overview

LocationSearch pulls named points of interest (POIs) from [Overture Maps](https://overturemaps.org/) and [OpenStreetMap](https://www.openstreetmap.org/) across a set of city bounding boxes (currently New York City, Washington D.C., and San Francisco), deduplicates and merges records that represent the same real-world place across sources, and builds an in-memory trie-based prefix-search index over the result. Full place details are hydrated from Redis. Two gRPC APIs are exposed on top of this:

1. **Prefix search** — given a prefix, a user location, and a requested top-k, returns the top-k places ranked by a blend of popularity and proximity to the user.
2. **Place details** — given a place id, returns the full place record.

## Pipeline

- `BuildCityDatasetOvertureImpl` / `BuildCityDatasetOsmImpl` — query Overture's Parquet-over-S3 dataset and local OSM PBF extracts (via DuckDB) for named POIs within a bounding box.
- `ConvergeDatasetService` — deduplicates POIs representing the same place across sources (grid-based spatial blocking + Jaro-Winkler name similarity + union-find clustering) and fuses each duplicate group into a single `MergedPoi`, including a `seedScore` popularity proxy derived from source confidence, source count, and weak notability signals (Wikipedia/Wikidata tags, web presence, destination-category bonus).
- `PoiDatasetStartup` — runs the pipeline across all configured city bounding boxes and writes the merged dataset to `src/main/resources/merged-pois.json`.
- `TrieBuilder` — builds an in-memory trie over normalized place names and aliases, keeping the top 50 places by `seedScore` at every prefix node.
- `RedisClient` / `scripts/load_pois_into_redis.py` — hydration store for full `MergedPoi` records, keyed by place id.
- `PlaceSearchServer` / `PlaceSearchServiceImpl` — gRPC server exposing the prefix-search and place-details APIs, ranking prefix-search candidates with `PlaceRanker` (haversine distance → exponential decay closeness, blended with normalized popularity).

## Running locally

```
docker-compose up -d                                    # start Redis
mvn -q compile                                           # generates gRPC/protobuf stubs
java -cp target/classes:<deps> org.learning.setup.PoiDatasetStartup   # (re)build merged-pois.json
python3 scripts/load_pois_into_redis.py                  # load dataset into Redis
java -cp target/classes:<deps> org.learning.grpc.PlaceSearchServer    # start the gRPC server on :50051
```
