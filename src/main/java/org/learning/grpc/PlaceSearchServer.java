package org.learning.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.learning.kafka.SearchEventProducer;
import org.learning.model.ScoredPlace;
import org.learning.ranking.RankingIndexProvider;
import org.learning.redis.RedisClient;
import org.learning.setup.TrieBuilder;
import org.learning.setup.TrieSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PlaceSearchServer {

    private static final int PORT = 50051;
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";

    // MinIO/S3 where TrieRebuildJob publishes behaviorally-ranked snapshots.
    private static final String MINIO_ENDPOINT = "http://localhost:9100";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String SNAPSHOT_BUCKET = "trie-snapshot";

    public static void main(String[] args) throws IOException, InterruptedException {
        // Bootstrap the serving index from a seed-only build (no behavioral metrics yet) so the
        // server ranks sensibly from the first request. The RankingIndexProvider then swaps in the
        // published behavioral snapshot as soon as one is available, and on every rebuild after.
        Map<String, List<ScoredPlace>> bootstrapIndex =
                TrieSnapshot.toIndex(new TrieBuilder().buildWithMetrics(List.of(), List.of()));
        RankingIndexProvider indexProvider = new RankingIndexProvider(
                MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, SNAPSHOT_BUCKET, bootstrapIndex);
        indexProvider.start();

        RedisClient redisClient = new RedisClient(REDIS_HOST, REDIS_PORT);
        SearchEventProducer eventProducer = new SearchEventProducer(KAFKA_BOOTSTRAP_SERVERS);

        Server server = ServerBuilder.forPort(PORT)
                .addService(new PlaceSearchServiceImpl(indexProvider, redisClient, eventProducer))
                .build()
                .start();

        System.out.println("PlaceSearchServer started on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            indexProvider.close();
            redisClient.close();
            eventProducer.close();
        }));

        server.awaitTermination();
    }
}
