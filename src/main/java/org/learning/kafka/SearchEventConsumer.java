package org.learning.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.learning.service.S3EventService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class SearchEventConsumer implements Runnable, AutoCloseable {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    // Flush to S3 after collecting this many records or when the poll returns empty
    private static final int FLUSH_BATCH_SIZE = 500;

    private final KafkaConsumer<String, String> consumer;
    private final S3EventService s3Writer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean running = true;

    private final List<String> prefixSearchBuffer = new ArrayList<>();
    private final List<String> placeSelectionBuffer = new ArrayList<>();

    // Tracks the oldest event timestamp in each buffer for S3 key dating.
    // Events are keyed by their own timestampMs so the S3 folder reflects
    // when the event happened, not when the consumer flushed it.
    private long prefixSearchEarliestTs = Long.MAX_VALUE;
    private long placeSelectionEarliestTs = Long.MAX_VALUE;

    public SearchEventConsumer(String bootstrapServers, String groupId, S3EventService s3Writer) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Commit only after a successful S3 flush so no events are lost on crash.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        this.consumer = new KafkaConsumer<>(props);
        this.s3Writer = s3Writer;

        consumer.subscribe(List.of(
                SearchEventProducer.TOPIC_PREFIX_SEARCH,
                SearchEventProducer.TOPIC_PLACE_SELECTION
        ));
    }

    @Override
    public void run() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_INTERVAL);

                for (ConsumerRecord<String, String> record : records) {
                    long ts = extractTimestamp(record.value());
                    if (SearchEventProducer.TOPIC_PREFIX_SEARCH.equals(record.topic())) {
                        prefixSearchBuffer.add(record.value());
                        if (ts < prefixSearchEarliestTs) prefixSearchEarliestTs = ts;
                    } else {
                        placeSelectionBuffer.add(record.value());
                        if (ts < placeSelectionEarliestTs) placeSelectionEarliestTs = ts;
                    }
                }

                boolean shouldFlush = records.isEmpty()
                        || prefixSearchBuffer.size() >= FLUSH_BATCH_SIZE
                        || placeSelectionBuffer.size() >= FLUSH_BATCH_SIZE;

                if (shouldFlush) {
                    flush();
                    consumer.commitSync();
                }
            }
        } finally {
            // Flush whatever remains before shutdown
            flush();
            consumer.commitSync();
            consumer.close();
        }
    }

    private void flush() {
        if (!prefixSearchBuffer.isEmpty()) {
            String content = String.join("\n", prefixSearchBuffer);
            s3Writer.write("prefix-search", content, prefixSearchEarliestTs);
            prefixSearchBuffer.clear();
            prefixSearchEarliestTs = Long.MAX_VALUE;
        }
        if (!placeSelectionBuffer.isEmpty()) {
            String content = String.join("\n", placeSelectionBuffer);
            s3Writer.write("place-selection", content, placeSelectionEarliestTs);
            placeSelectionBuffer.clear();
            placeSelectionEarliestTs = Long.MAX_VALUE;
        }
    }

    private long extractTimestamp(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode ts = node.get("timestampMs");
            return ts != null ? ts.asLong() : System.currentTimeMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    @Override
    public void close() {
        running = false;
    }
}
