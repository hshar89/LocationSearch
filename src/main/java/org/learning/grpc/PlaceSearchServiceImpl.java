package org.learning.grpc;

import io.grpc.stub.StreamObserver;
import org.learning.grpc.proto.GetPlaceDetailsRequest;
import org.learning.grpc.proto.GetPlaceDetailsResponse;
import org.learning.grpc.proto.Place;
import org.learning.grpc.proto.PlaceSearchServiceGrpc;
import org.learning.grpc.proto.PlaceSummary;
import org.learning.grpc.proto.SearchPrefixRequest;
import org.learning.grpc.proto.SearchPrefixResponse;
import org.learning.model.MergedPoi;
import org.learning.model.Trie;
import org.learning.ranking.PlaceRanker;
import org.learning.redis.RedisClient;

import java.util.Comparator;
import java.util.List;

import static org.learning.BuilderUtil.normalize;

public class PlaceSearchServiceImpl extends PlaceSearchServiceGrpc.PlaceSearchServiceImplBase {

    private final Trie trieRoot;
    private final RedisClient redisClient;

    public PlaceSearchServiceImpl(Trie trieRoot, RedisClient redisClient) {
        this.trieRoot = trieRoot;
        this.redisClient = redisClient;
    }

    @Override
    public void searchPrefix(SearchPrefixRequest request, StreamObserver<SearchPrefixResponse> responseObserver) {
        Trie node = findNode(normalize(request.getPrefix()));
        List<String> candidateIds = node == null ? List.of() : node.getPlaceIds();

        SearchPrefixResponse.Builder response = SearchPrefixResponse.newBuilder();
        candidateIds.stream()
                .map(redisClient::getPoi)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble((MergedPoi poi) ->
                        PlaceRanker.score(request.getLat(), request.getLng(), poi.lat(), poi.lng(), poi.seedScore())
                ).reversed())
                .limit(request.getTopK())
                .forEach(poi -> response.addPlaces(PlaceSummary.newBuilder()
                        .setId(poi.id())
                        .setDisplayName(poi.display_name())
                        .build()));

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
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
