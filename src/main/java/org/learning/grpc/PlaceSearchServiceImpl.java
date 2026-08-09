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
import org.learning.model.Trie;
import org.learning.ranking.PlaceRanker;
import org.learning.redis.RedisClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.learning.BuilderUtil.normalize;

public class PlaceSearchServiceImpl extends PlaceSearchServiceGrpc.PlaceSearchServiceImplBase {

    private final Trie trieRoot;
    private final RedisClient redisClient;
    private final SearchEventProducer eventProducer;

    public PlaceSearchServiceImpl(Trie trieRoot, RedisClient redisClient, SearchEventProducer eventProducer) {
        this.trieRoot = trieRoot;
        this.redisClient = redisClient;
        this.eventProducer = eventProducer;
    }

    @Override
    public void searchPrefix(SearchPrefixRequest request, StreamObserver<SearchPrefixResponse> responseObserver) {
        Trie node = findNode(normalize(request.getPrefix()));
        List<String> candidateIds = node == null ? List.of() : node.getPlaceIds();

        List<MergedPoi> ranked = candidateIds.stream()
                .map(redisClient::getPoi)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((MergedPoi poi) ->
                        PlaceRanker.score(request.getLat(), request.getLng(), poi.lat(), poi.lng(), poi.seedScore())
                ).reversed())
                .limit(request.getTopK())
                .toList();

        SearchPrefixResponse.Builder response = SearchPrefixResponse.newBuilder();
        List<String> resultIds = new ArrayList<>();
        for (MergedPoi poi : ranked) {
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

    private Trie findNode(String normalizedPrefix) {
        Trie node = trieRoot;
        for (int i = 0; i < normalizedPrefix.length(); i++) {
            node = node.getChildren().get(normalizedPrefix.charAt(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }
}
