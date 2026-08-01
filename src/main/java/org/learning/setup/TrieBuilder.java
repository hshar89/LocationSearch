package org.learning.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.MergedPoi;
import org.learning.model.Trie;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.learning.BuilderUtil.normalize;

public class TrieBuilder {

    private static final String DATASET_PATH = "src/main/resources/merged-pois.json";

    private final Trie root = new Trie();

    private static final int TOP_PLACES = 100;

    public Trie build() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        List<MergedPoi> list = objectMapper.readValue(new File(DATASET_PATH),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MergedPoi.class));

        Map<Trie, HeapContainer> topPlacesHeapMap = new HashMap<>();
        for (MergedPoi mergedPoi : list) {
            String normalizedPrimary = normalize(mergedPoi.display_name());
            List<String> aliases = mergedPoi.normalizedAliases();
            addToTree(normalizedPrimary, 0, mergedPoi, root, topPlacesHeapMap);
            for (String alias : aliases) {
                addToTree(alias, 0, mergedPoi, root, topPlacesHeapMap);
            }
        }

        rerankTrie(topPlacesHeapMap);
        return root;
    }

    private void rerankTrie(Map<Trie, HeapContainer> topPlacesInHeapMap){
        for(Map.Entry<Trie, HeapContainer> entry: topPlacesInHeapMap.entrySet()){
            Trie trie = entry.getKey();
            HeapContainer container = entry.getValue();
            List<String> placeIds = new ArrayList<>();
            while(!container.heap.isEmpty()){
                placeIds.add(container.heap.poll().placeId);
            }
            // heap is a min-heap, so draining it yields ascending score order;
            // reverse so callers get highest score first.
            Collections.reverse(placeIds);
            trie.setPlaceIds(placeIds);
        }
    }

    private void addToTree(String s, int idx, MergedPoi mergedPoi, Trie node, Map<Trie, HeapContainer> topPlacesHeapMap){
        if(idx>=s.length()){
            node.setTerminal(true);
            return;
        }
        Map<Character, Trie> children = node.getChildren();
        char ch = s.charAt(idx);
        Trie child_node = children.computeIfAbsent(ch, v->new Trie(ch));
        offer(child_node, mergedPoi, topPlacesHeapMap);
        addToTree(s, idx+1, mergedPoi, child_node, topPlacesHeapMap);
    }

    private void offer(Trie node, MergedPoi mergedPoi, Map<Trie, HeapContainer> topPlacesHeapMap){
        HeapContainer container = topPlacesHeapMap.computeIfAbsent(node, v -> new HeapContainer());
        if(container.placeIdsInHeap.contains(mergedPoi.id())){
            return;
        }
        if(container.heap.size()<TOP_PLACES){
            container.heap.add(new Pair(mergedPoi.id(), mergedPoi.seedScore()));
            container.placeIdsInHeap.add(mergedPoi.id());
        } else if(container.heap.peek().score<mergedPoi.seedScore()){
            Pair evicted = container.heap.poll();
            container.placeIdsInHeap.remove(evicted.placeId);
            container.heap.add(new Pair(mergedPoi.id(), mergedPoi.seedScore()));
            container.placeIdsInHeap.add(mergedPoi.id());
        }
    }

    private static class HeapContainer{
        private final PriorityQueue<TrieBuilder.Pair> heap;
        private final Set<String> placeIdsInHeap;

        public HeapContainer() {
            this.heap = new PriorityQueue<>(TOP_PLACES, Comparator.comparingDouble(a -> a.score));
            this.placeIdsInHeap = new HashSet<>();
        }
    }

    private record Pair(
       String placeId,
       double score
    ){
    }
}
