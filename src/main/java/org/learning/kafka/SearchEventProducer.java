package org.learning.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.learning.model.PlaceSelectionEvent;
import org.learning.model.PrefixSearchEvent;

import java.util.List;
import java.util.Properties;

public class SearchEventProducer implements AutoCloseable {

    public static final String TOPIC_PREFIX_SEARCH = "place-search-prefix";
    public static final String TOPIC_PLACE_SELECTION = "place-search-selection";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchEventProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Fire-and-forget: don't block the gRPC response waiting for broker ack.
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        this.producer = new KafkaProducer<>(props);
    }

    public void emitPrefixSearch(String sessionId, String prefix, List<String> resultIds,
                                  double userLat, double userLng) {
        PrefixSearchEvent event = new PrefixSearchEvent(sessionId, prefix, resultIds, userLat, userLng,
                System.currentTimeMillis());
        send(TOPIC_PREFIX_SEARCH, sessionId, event);
    }

    public void emitPlaceSelection(String sessionId, String placeId) {
        PlaceSelectionEvent event = new PlaceSelectionEvent(sessionId, placeId, System.currentTimeMillis());
        send(TOPIC_PLACE_SELECTION, sessionId, event);
    }

    private void send(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // keyed by sessionId so all events for one session land on the same partition,
            // preserving order for the downstream batch job.
            producer.send(new ProducerRecord<>(topic, key, json), (metadata, ex) -> {
                if (ex != null) {
                    System.err.println("Failed to deliver Kafka event to " + topic + ": " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            // non-fatal — event loss is acceptable, never let this propagate to the caller.
            System.err.println("Failed to send Kafka event: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        producer.close();
    }

}
