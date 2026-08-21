package org.learning.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.MergedPoi;
import org.learning.model.PlaceMetrics;
import org.learning.model.PrefixPlaceCombinationMetrics;
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
    private static final double SEED_SCORE_CAP = 10.0;

    // Weights for the blended node score formula.
    private static final double W1_POPULARITY      = 0.30;
    private static final double W2_SELECTION_RATE  = 0.25;
    private static final double W3_MRR             = 0.25;
    private static final double W4_EFFORT_PENALTY  = 0.10;
    private static final double W5_ABANDON_RATE    = 0.10;

    // Effort penalty sub-weights: how much gap-to-top vs avg selection prefix len matter.
    private static final double EFFORT_A = 0.5;  // weight for (avgFirstTop - avgFirstAppearance)
    private static final double EFFORT_B = 0.5;  // weight for avgPrefixLenOnSelection

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

    // Rebuilds the trie using a blended score that incorporates behavioral signals from
    // the collected PrefixPlaceCombinationMetrics and PlaceMetrics.
    //
    // Score at node for (prefix, placeId):
    //   w1 * popularity(place)          — normalized seedScore, same everywhere the place appears
    //   w2 * selectionRate(prefix,place) — selections / impressions for this edge
    //   w3 * MRR(prefix,place)           — sumReciprocalRank / selections for this edge
    //   w4 * effortPenalty(place)        — a*(avgFirstTop - avgFirstAppearance) + b*avgPrefixLenOnSelection
    //   w5 * abandonmentRate(prefix)     — total abandonments / total impressions across all places at prefix
    public Trie buildWithMetrics(
            List<PrefixPlaceCombinationMetrics> comboMetrics,
            List<PlaceMetrics> placeMetrics) throws IOException {

        // --- Build lookup maps ---
        // combo key: "<prefix>|<placeId>"
        Map<String, PrefixPlaceCombinationMetrics> comboMap = new HashMap<>();
        for (PrefixPlaceCombinationMetrics m : comboMetrics) {
            comboMap.put(m.getPrefix() + "|" + m.getPlaceId(), m);
        }

        Map<String, PlaceMetrics> placeMap = new HashMap<>();
        for (PlaceMetrics m : placeMetrics) {
            placeMap.put(m.getPlaceId(), m);
        }

        // abandonmentRate is prefix-level: sum abandonments / sum impressions across all combos
        // sharing the same prefix.
        Map<String, long[]> prefixTotals = new HashMap<>(); // [totalAbandonments, totalImpressions]
        for (PrefixPlaceCombinationMetrics m : comboMetrics) {
            long[] t = prefixTotals.computeIfAbsent(m.getPrefix(), k -> new long[]{0L, 0L});
            t[0] += m.getAbandonmentCounter();
            t[1] += m.getImpressions();
        }

        // --- Build trie with blended scores ---
        ObjectMapper objectMapper = new ObjectMapper();
        List<MergedPoi> pois = objectMapper.readValue(new File(DATASET_PATH),
                objectMapper.getTypeFactory().constructCollectionType(List.class, MergedPoi.class));

        Map<Trie, HeapContainer> topPlacesHeapMap = new HashMap<>();
        for (MergedPoi poi : pois) {
            String normalizedPrimary = normalize(poi.display_name());
            addToTreeWithScore(normalizedPrimary, 0, poi, root, topPlacesHeapMap,
                    comboMap, placeMap, prefixTotals);
            for (String alias : poi.normalizedAliases()) {
                addToTreeWithScore(alias, 0, poi, root, topPlacesHeapMap,
                        comboMap, placeMap, prefixTotals);
            }
        }

        rerankTrie(topPlacesHeapMap);
        return root;
    }

    // Computes the blended score for a specific (prefix, placeId) edge.
    // Falls back to seedScore-only when no behavioral data exists yet.
    private double blendedScore(String prefix, MergedPoi poi,
                                Map<String, PrefixPlaceCombinationMetrics> comboMap,
                                Map<String, PlaceMetrics> placeMap,
                                Map<String, long[]> prefixTotals) {
        double popularity = Math.min(poi.seedScore() / SEED_SCORE_CAP, 1.0);

        String key = prefix + "|" + poi.id();
        PrefixPlaceCombinationMetrics combo = comboMap.get(key);
        PlaceMetrics pm = placeMap.get(poi.id());

        if (combo == null || combo.getImpressions() == 0) {
            // No behavioral data — fall back to popularity only so new places still surface.
            return popularity;
        }

        double selectionRate = (double) combo.getSelections() / combo.getImpressions();
        double mrr = combo.getSelections() > 0
                ? combo.getSumReciprocalRank() / combo.getSelections()
                : 0.0;

        long[] totals = prefixTotals.getOrDefault(prefix, new long[]{0L, 1L});
        double abandonmentRate = totals[1] > 0 ? (double) totals[0] / totals[1] : 0.0;

        double effortPenalty = 0.0;
        if (pm != null && pm.getCountFirstAppearances() > 0) {
            double avgFirstAppearance = (double) pm.getSumPrefixLenOnFirstAppearance() / pm.getCountFirstAppearances();
            double avgFirstTop = pm.getCountFirstTop() > 0
                    ? (double) pm.getSumPrefixLenOnFirstTop() / pm.getCountFirstTop()
                    : avgFirstAppearance;
            double avgPrefixLenOnSelection = pm.getCountSelections() > 0
                    ? (double) pm.getSumPrefixLenOnSelection() / pm.getCountSelections()
                    : 0.0;
            effortPenalty = EFFORT_A * (avgFirstTop - avgFirstAppearance) + EFFORT_B * avgPrefixLenOnSelection;
            // Normalize to [0,1] assuming max meaningful prefix length of 20 chars.
            effortPenalty = Math.min(effortPenalty / 20.0, 1.0);
        }

        return W1_POPULARITY * popularity
                + W2_SELECTION_RATE * selectionRate
                + W3_MRR * mrr
                - W4_EFFORT_PENALTY * effortPenalty
                - W5_ABANDON_RATE * abandonmentRate;
    }

    private void addToTreeWithScore(String s, int idx, MergedPoi poi, Trie node,
                                    Map<Trie, HeapContainer> topPlacesHeapMap,
                                    Map<String, PrefixPlaceCombinationMetrics> comboMap,
                                    Map<String, PlaceMetrics> placeMap,
                                    Map<String, long[]> prefixTotals) {
        if (idx >= s.length()) {
            node.setTerminal(true);
            return;
        }
        Map<Character, Trie> children = node.getChildren();
        char ch = s.charAt(idx);
        Trie childNode = children.computeIfAbsent(ch, v -> new Trie(ch));

        // Prefix at this child node is s[0..idx] inclusive. Score is computed here,
        // per node, so each trie position uses the behavioral signals specific to that prefix.
        String prefixAtNode = s.substring(0, idx + 1);
        double score = blendedScore(prefixAtNode, poi, comboMap, placeMap, prefixTotals);
        offerWithScore(childNode, poi.id(), score, topPlacesHeapMap);
        addToTreeWithScore(s, idx + 1, poi, childNode, topPlacesHeapMap,
                comboMap, placeMap, prefixTotals);
    }

    private void offerWithScore(Trie node, String placeId, double score,
                                Map<Trie, HeapContainer> topPlacesHeapMap) {
        HeapContainer container = topPlacesHeapMap.computeIfAbsent(node, v -> new HeapContainer());
        if (container.placeIdsInHeap.contains(placeId)) {
            return;
        }
        if (container.heap.size() < TOP_PLACES) {
            container.heap.add(new Pair(placeId, score));
            container.placeIdsInHeap.add(placeId);
        } else if (container.heap.peek().score < score) {
            Pair evicted = container.heap.poll();
            container.placeIdsInHeap.remove(evicted.placeId);
            container.heap.add(new Pair(placeId, score));
            container.placeIdsInHeap.add(placeId);
        }
    }

    private void rerankTrie(Map<Trie, HeapContainer> topPlacesInHeapMap){
        for(Map.Entry<Trie, HeapContainer> entry: topPlacesInHeapMap.entrySet()){
            Trie trie = entry.getKey();
            HeapContainer container = entry.getValue();
            List<String> placeIds = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            while(!container.heap.isEmpty()){
                Pair pair = container.heap.poll();
                placeIds.add(pair.placeId);
                scores.add(pair.score);
            }
            // heap is a min-heap, so draining it yields ascending score order;
            // reverse so callers get highest score first. placeIds and scores stay aligned.
            Collections.reverse(placeIds);
            Collections.reverse(scores);
            trie.setPlaceIds(placeIds);
            trie.setScores(scores);
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
