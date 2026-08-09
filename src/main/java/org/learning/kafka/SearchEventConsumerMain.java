package org.learning.kafka;

import org.learning.service.S3EventService;

public class SearchEventConsumerMain {

    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String CONSUMER_GROUP_ID = "search-event-consumer";
    private static final String MINIO_ENDPOINT = "http://localhost:9100";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String BUCKET = "search-events";

    public static void main(String[] args) throws InterruptedException {
        S3EventService s3Writer = new S3EventService(MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, BUCKET);
        SearchEventConsumer consumer = new SearchEventConsumer(KAFKA_BOOTSTRAP_SERVERS, CONSUMER_GROUP_ID, s3Writer);

        Thread consumerThread = new Thread(consumer, "search-event-consumer");
        consumerThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            try {
                consumerThread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        System.out.println("SearchEventConsumer started. Polling Kafka and writing to MinIO...");
        consumerThread.join();
    }
}
