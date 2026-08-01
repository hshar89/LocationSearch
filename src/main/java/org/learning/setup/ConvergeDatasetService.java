package org.learning.setup;


import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.learning.BuilderUtil;
import org.learning.exception.BuildDatasetException;
import org.learning.model.MergedPoi;
import org.learning.model.Poi;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.learning.BuilderUtil.normalize;

public class ConvergeDatasetService {

    // Grid cell size in meters: 2x the ~100m dedup distance threshold, so any
    // true duplicate pair is guaranteed to land in the same or an adjacent cell.
    private static final double CELL_SIZE_METERS = 200.0;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;
    // Bbox sits in one latitude band (NYC area); longitude degrees shrink by
    // cos(latitude) in real distance, so this corrects cell width accordingly.
    private static final double REFERENCE_LATITUDE_DEGREES = 40.7;

    private static final double LAT_CELL_SIZE_DEGREES = CELL_SIZE_METERS / METERS_PER_DEGREE_LAT;
    private static final double LON_CELL_SIZE_DEGREES =
            CELL_SIZE_METERS / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(REFERENCE_LATITUDE_DEGREES)));

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private static final double JARO_WINKLER_THRESHOLD = 0.9;
    private static final double DISTANCE_BETWEEN_POINTS_THRESHOLD = 50.0d;
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    private static final int ROW[] = new int[]{-1,-1,-1,0,1,1,1,0};
    private static final int COL[] = new int[]{-1,0,1,1,1,0,-1,-1};

    // Lower index = higher trust when picking the canonical name/category for a
    // merged group. Sources not in this list fall back to lowest priority.
    private static final List<String> SOURCE_PRIORITY = List.of("OVERTURE", "OSM");

    private final List<BuildCityDataset> buildCityDatasetList;

    public ConvergeDatasetService(List<BuildCityDataset> buildCityDatasetList) {
        this.buildCityDatasetList = buildCityDatasetList;
    }

    public List<Poi> getDataSet(double lon1, double lat1, double lon2, double lat2){
        List<Poi> collectedPois = new ArrayList<>();
        for(BuildCityDataset buildCityDataset: buildCityDatasetList){
            try{
                collectedPois.addAll(buildCityDataset.fetchNamedPois(lon1, lat1, lon2, lat2));
            } catch (BuildDatasetException ex){
                System.out.println(ex);
            }
        }
        return collectedPois;
    }

    public List<MergedPoi> filterDataset(List<Poi> collectedPois){
        Map<GridCell, List<Poi>> poisIntoBlocks = blockPois(collectedPois);
        IdentityHashMap<Poi, Poi> parentMap = new IdentityHashMap<>();
        IdentityHashMap<Poi, Integer> rankMap = new IdentityHashMap<>();
        for(Map.Entry<GridCell, List<Poi>> entry: poisIntoBlocks.entrySet()){
            GridCell grid = entry.getKey();
            List<Poi> pois = entry.getValue();
            //compare within the block
            for(int i=0;i<pois.size();i++){
                for(int j=i+1;j<pois.size();j++){
                    Poi a = pois.get(i);
                    Poi b = pois.get(j);
                    if(isDuplicate(a, b)){
                        Poi parentA = findParent(a, parentMap);
                        Poi parentB = findParent(b, parentMap);
                        union(parentA, parentB, parentMap, rankMap);
                    }
                }
            }
            //compare with neighbouring blocks.
            for(Poi poi: pois){
                for(int i=0;i<8;i++){
                    GridCell neigh = new GridCell(grid.row+ ROW[i], grid.col+COL[i]);
                    List<Poi> neighPois = poisIntoBlocks.get(neigh);
                    if(neighPois!=null){
                        for(Poi b: neighPois){
                            if(isDuplicate(poi, b)){
                                Poi parentA = findParent(poi, parentMap);
                                Poi parentB = findParent(b, parentMap);
                                union(parentA, parentB, parentMap, rankMap);
                            }
                        }
                    }
                }
            }
        }
        IdentityHashMap<Poi, List<Poi>> groups = new IdentityHashMap<>();
        collectedPois.forEach(poi->{
            Poi parent = findParent(poi, parentMap);
            groups.computeIfAbsent(parent, v->new ArrayList<>()).add(poi);
        });
        List<MergedPoi> mergedPois = new ArrayList<>();
        groups.values().stream().forEach(group->{
            Poi canonical = group.stream()
                    .min(java.util.Comparator.comparingInt(ConvergeDatasetService::sourcePriority))
                    .get();
            String displayName = canonical.displayName();

            List<String> aliases = group.stream()
                    .flatMap(poi -> java.util.stream.Stream.concat(
                            poi == canonical ? java.util.stream.Stream.<String>empty() : java.util.stream.Stream.of(poi.name()),
                            poi.aliases().stream()))
                    .map(BuilderUtil::normalize)
                    .distinct()
                    .toList();

            List<String> categories = group.stream().map(Poi::category).distinct().toList();
            double confidence = group.stream().map(Poi::confidence).max(Double::compareTo).get();
            double lat = group.stream().mapToDouble(Poi::lat).average().getAsDouble();
            double lng = group.stream().mapToDouble(Poi::lon).average().getAsDouble();
            List<String> sources = group.stream().map(Poi::source).distinct().toList();
            String id = java.util.UUID.randomUUID().toString();
            double seedScore = computeSeedScore(group, confidence, sources.size());
            mergedPois.add(new MergedPoi(id, displayName, aliases, categories, confidence, lat, lng, sources, seedScore));
        });
        return mergedPois;
    }

    private void union(Poi parentA, Poi parentB, IdentityHashMap<Poi, Poi> parentMap, IdentityHashMap<Poi, Integer> rankMap){
        if(parentA==parentB){
            return;
        }
        int rankA = rankMap.getOrDefault(parentA, 0);
        int rankB = rankMap.getOrDefault(parentB, 0);
        if(rankA<rankB){
            parentMap.put(parentA, parentB);
        } else if(rankB<rankA){
            parentMap.put(parentB, parentA);
        } else {
            parentMap.put(parentB, parentA);
            rankMap.put(parentA, rankA+1);
        }
    }

    private Poi findParent(Poi p, IdentityHashMap<Poi, Poi> parentMap){
        Poi parent = parentMap.get(p);
        if(parent==null){
            return p;
        }
        Poi root = findParent(parent, parentMap);
        parentMap.put(p, root);
        return root;
    }

    private record GridCell(int row, int col) {
    }

    // Hand-tuned weights combining confidence/source-count with the weak
    // notability signals pulled from each source (wiki entry, web presence,
    // upstream provider count). A wiki entry is the strongest signal since it
    // means an independent party considered the place notable; the others are
    // softer corroboration.
    private static final double SOURCE_SIGNAL_WEIGHT = 0.1;
    private static final double WIKI_ENTRY_WEIGHT = 2.0;
    private static final double WEB_PRESENCE_WEIGHT = 0.5;
    private static final double DESTINATION_CATEGORY_WEIGHT = 1.5;

    // Categories people deliberately travel to, as opposed to everyday
    // businesses passed by incidentally. Flat bonus, no per-category ranking
    // within the set since the data doesn't support finer tuning.
    private static final Set<String> DESTINATION_CATEGORIES = Set.of(
            "landmark_and_historical_building", "monument", "train_station",
            "subway_station", "bus_station", "temple", "resort", "stadium",
            "university", "college"
    );

    private static double computeSeedScore(List<Poi> group, double confidence, int sourceCount) {
        int totalSourceSignalCount = group.stream().mapToInt(Poi::sourceSignalCount).sum();
        boolean hasWikiEntry = group.stream().anyMatch(Poi::hasWikiEntry);
        boolean hasWebPresence = group.stream().anyMatch(Poi::hasWebPresence);
        boolean isDestinationCategory = group.stream().map(Poi::category).anyMatch(DESTINATION_CATEGORIES::contains);

        return confidence * sourceCount
                + SOURCE_SIGNAL_WEIGHT * totalSourceSignalCount
                + (hasWikiEntry ? WIKI_ENTRY_WEIGHT : 0.0)
                + (hasWebPresence ? WEB_PRESENCE_WEIGHT : 0.0)
                + (isDestinationCategory ? DESTINATION_CATEGORY_WEIGHT : 0.0);
    }

    private static int sourcePriority(Poi poi) {
        int index = SOURCE_PRIORITY.indexOf(poi.source());
        return index == -1 ? SOURCE_PRIORITY.size() : index;
    }

    private boolean isDuplicate(Poi a, Poi b){
        //proximity
        double distance = distanceMeters(a, b);
        //name similarity
        return distance<=DISTANCE_BETWEEN_POINTS_THRESHOLD && nameMatches(a, b);
    }

    private static double distanceMeters(Poi a, Poi b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double deltaLat = Math.toRadians(b.lat() - a.lat());
        double deltaLon = Math.toRadians(b.lon() - a.lon());

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);
        double h = sinHalfLat * sinHalfLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

        return EARTH_RADIUS_METERS * c;
    }

    private static boolean nameMatches(Poi a, Poi b) {
        Set<String> namesA = normalizedNames(a);
        Set<String> namesB = normalizedNames(b);

        if (!Collections.disjoint(namesA, namesB)) {
            return true;
        }

        String primaryA = normalize(a.name());
        String primaryB = normalize(b.name());
        return JARO_WINKLER.apply(primaryA, primaryB) >= JARO_WINKLER_THRESHOLD;
    }

    private static Set<String> normalizedNames(Poi poi) {
        Set<String> names = new HashSet<>();
        names.add(normalize(poi.name()));
        for (String alias : poi.aliases()) {
            names.add(normalize(alias));
        }
        return names;
    }

    private static GridCell cellFor(Poi poi) {
        int row = (int) Math.floor(poi.lat() / LAT_CELL_SIZE_DEGREES);
        int col = (int) Math.floor(poi.lon() / LON_CELL_SIZE_DEGREES);
        return new GridCell(row, col);
    }

    private static Map<GridCell, List<Poi>> blockPois(List<Poi> pois) {
        Map<GridCell, List<Poi>> blocks = new HashMap<>();
        for (Poi poi : pois) {
            blocks.computeIfAbsent(cellFor(poi), key -> new ArrayList<>()).add(poi);
        }
        return blocks;
    }

}
