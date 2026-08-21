package org.learning.grpc;

import io.grpc.stub.StreamObserver;
import org.learning.grpc.proto.GetPlaceDetailsRequest;
import org.learning.grpc.proto.GetPlaceDetailsResponse;
import org.learning.grpc.proto.Place;
import org.learning.grpc.proto.PlaceSearchServiceGrpc;
import org.learning.grpc.proto.PlaceSummary;
import org.learning.grpc.proto.SearchPrefixRequest;
import org.learning.grpc.proto.SearchPrefixResponse;
import org.learning.kafka.SearchEventProducer;
import org.learning.model.MergedPoi;
import org.learning.model.ScoredPlace;
import org.learning.ranking.PlaceRanker;
import org.learning.ranking.RankingIndexProvider;
import org.learning.redis.RedisClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.learning.BuilderUtil.normalize;

public class PlaceSearchServiceImpl extends PlaceSearchServiceGrpc.PlaceSearchServiceImplBase {

    private final RankingIndexProvider indexProvider;
    private final RedisClient redisClient;
    private final SearchEventProducer eventProducer;

    public PlaceSearchServiceImpl(RankingIndexProvider indexProvider, RedisClient redisClient,
                                  SearchEventProducer eventProducer) {
        this.indexProvider = indexProvider;
        this.redisClient = redisClient;
        this.eventProducer = eventProducer;
    }

    // A candidate hydrated with its POI record and final blended score, ready to sort.
    private record RankedPlace(MergedPoi poi, double score) {
    }

    @Override
    public void searchPrefix(SearchPrefixRequest request, StreamObserver<SearchPrefixResponse> responseObserver) {
        List<ScoredPlace> candidates = indexProvider.current()
                .getOrDefault(normalize(request.getPrefix()), List.of());

        List<RankedPlace> ranked = candidates.stream()
                .map(candidate -> {
                    MergedPoi poi = redisClient.getPoi(candidate.id());
                    if (poi == null) {
                        return null;
                    }
                    // Blend the prefix-specific behavioral score with proximity to the user.
                    double score = PlaceRanker.blend(candidate.score(),
                            request.getLat(), request.getLng(), poi.lat(), poi.lng());
                    return new RankedPlace(poi, score);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(RankedPlace::score).reversed())
                .limit(request.getTopK())
                .toList();

        SearchPrefixResponse.Builder response = SearchPrefixResponse.newBuilder();
        List<String> resultIds = new ArrayList<>();
        for (RankedPlace rankedPlace : ranked) {
            MergedPoi poi = rankedPlace.poi();
            response.addPlaces(PlaceSummary.newBuilder()
                    .setId(poi.id())
                    .setDisplayName(poi.display_name())
                    .build());
            resultIds.add(poi.id());
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();

        if (!request.getSessionId().isBlank()) {
            eventProducer.emitPrefixSearch(request.getSessionId(), request.getPrefix(),
                    resultIds, request.getLat(), request.getLng());
        }
    }

    @Override
    public void getPlaceDetails(GetPlaceDetailsRequest request, StreamObserver<GetPlaceDetailsResponse> responseObserver) {
        MergedPoi poi = redisClient.getPoi(request.getPlaceId());
        if (poi == null) {
            responseObserver.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("No place found for id " + request.getPlaceId())
                    .asRuntimeException());
            return;
        }

        Place place = Place.newBuilder()
                .setId(poi.id())
                .setDisplayName(poi.display_name())
                .addAllAliases(poi.normalizedAliases())
                .addAllCategories(poi.categories())
                .setConfidence(poi.confidence())
                .setLat(poi.lat())
                .setLng(poi.lng())
                .addAllSources(poi.sources())
                .setSeedScore(poi.seedScore())
                .build();

        responseObserver.onNext(GetPlaceDetailsResponse.newBuilder().setPlace(place).build());
        responseObserver.onCompleted();

        if (!request.getSessionId().isBlank()) {
            eventProducer.emitPlaceSelection(request.getSessionId(), request.getPlaceId());
        }
    }
}
