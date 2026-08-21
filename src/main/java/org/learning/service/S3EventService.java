package org.learning.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class S3EventService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final String bucket;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public S3EventService(String endpoint, String accessKey, String secretKey, String bucket) {
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.US_EAST_1)
                // required for MinIO: use path-style access (bucket in path, not subdomain)
                .forcePathStyle(true)
                .build();
        this.bucket = bucket;
        ensureBucketExists();
    }

    public void writeKey(String key, String content) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/x-ndjson").build(),
                RequestBody.fromString(content)
        );
        log.debug("Wrote file: {}", key);
    }

    public void write(String eventType, String content, long timestampMs) {
        Instant instant = Instant.ofEpochMilli(timestampMs);
        String date = DATE_FORMAT.format(instant);
        String hour = HOUR_FORMAT.format(instant);
        String key = "search-events/" + eventType + "/" + date + "/" + hour + "/"
                + UUID.randomUUID() + "-" + timestampMs + ".json";

        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json").build(),
                RequestBody.fromString(content)
        );
        log.debug("Wrote event file: {}", key);
    }

    // Reads all event files under search-events/<eventType>/<date>/[<hourPrefix>/].
    // hourPrefix is optional — pass null to read the full day folder.
    public List<String> readEvents(String eventType, LocalDate date, String hourPrefix) {
        String prefix = "search-events/" + eventType + "/" + date;
        if (hourPrefix != null) {
            prefix = prefix + "/" + hourPrefix;
        }
        return readAllUnderPrefix(prefix + "/");
    }

    // Reads a single key, returning null if it does not exist. Used to read the snapshot "latest"
    // pointer, which is absent until the first trie rebuild has published a snapshot.
    public String readKeyOrNull(String key) {
        try (var response = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.error("Failed to read S3 key {}: {}", key, e.getMessage());
            return null;
        }
    }

    // General-purpose read for any key prefix in this bucket.
    // S3 folders are virtual: ListObjectsV2 enumerates all keys, then each is fetched individually.
    public List<String> readAllUnderPrefix(String keyPrefix) {
        List<String> results = new ArrayList<>();
        s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(keyPrefix)
                        .build())
                .contents()
                .forEach(s3Object -> results.add(readObject(s3Object)));

        log.info("Read {} files from prefix {}", results.size(), keyPrefix);
        return results;
    }

    // Deserializes a list of raw NDJSON file contents (each from readEvents()) into
    // typed event objects. Each file contains one JSON object per line.
    public <T> List<T> deserialize(List<String> rawFiles, Class<T> eventClass) {
        List<T> events = new ArrayList<>();
        for (String fileContent : rawFiles) {
            for (String line : fileContent.split("\n")) {
                if (line.isBlank()) continue;
                try {
                    events.add(objectMapper.readValue(line, eventClass));
                } catch (IOException e) {
                    log.error("Failed to deserialize line as {}: {}", eventClass.getSimpleName(), e.getMessage());
                }
            }
        }
        return events;
    }

    private String readObject(S3Object s3Object) {
        try (var response = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(s3Object.key()).build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to read S3 object {}: {}", s3Object.key(), e.getMessage());
            return "";
        }
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created bucket: {}", bucket);
        }
    }
}
