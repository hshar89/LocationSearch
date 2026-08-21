package org.learning.batch;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.learning.service.S3EventService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class BatchJobApplication {

    private static final String MINIO_ENDPOINT = "http://localhost:9100";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";

    public static void main(String[] args) {
        SpringApplication.run(BatchJobApplication.class, args);
    }

    @Bean
    public S3EventService s3EventService() {
        return new S3EventService(MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, "search-events");
    }

    @Bean
    public S3EventService metricsS3Service() {
        return new S3EventService(MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, "place-ranking-metric");
    }

    @Bean
    public S3EventService trieSnapshotS3Service() {
        return new S3EventService(MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, "trie-snapshot");
    }
}
