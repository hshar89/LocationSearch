package org.learning.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.learning.kafka.SearchEventProducer;
import org.learning.model.Trie;
import org.learning.redis.RedisClient;
import org.learning.setup.TrieBuilder;

import java.io.IOException;

public class PlaceSearchServer {

    private static final int PORT = 50051;
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";

    public static void main(String[] args) throws IOException, InterruptedException {
        Trie trieRoot = new TrieBuilder().build();
        RedisClient redisClient = new RedisClient(REDIS_HOST, REDIS_PORT);
        SearchEventProducer eventProducer = new SearchEventProducer(KAFKA_BOOTSTRAP_SERVERS);

        Server server = ServerBuilder.forPort(PORT)
                .addService(new PlaceSearchServiceImpl(trieRoot, redisClient, eventProducer))
                .build()
                .start();

        System.out.println("PlaceSearchServer started on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            redisClient.close();
            eventProducer.close();
        }));

        server.awaitTermination();
    }
}
