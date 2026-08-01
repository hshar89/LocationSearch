package org.learning.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.MergedPoi;
import org.learning.model.Poi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PoiDatasetStartup {

    private record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
    }

    private static final List<BoundingBox> CITY_BOUNDING_BOXES = List.of(
            new BoundingBox(-74.26, 40.49, -73.70, 40.92),  // New York City
            new BoundingBox(-77.12, 38.80, -76.90, 38.99),  // Washington, D.C.
            new BoundingBox(-122.52, 37.70, -122.35, 37.83) // San Francisco
    );

    private static final String OUTPUT_PATH = "src/main/resources/merged-pois.json";

    private final ConvergeDatasetService convergeDatasetService;

    public PoiDatasetStartup(ConvergeDatasetService convergeDatasetService) {
        this.convergeDatasetService = convergeDatasetService;
    }

    public List<MergedPoi> load() throws IOException {
        List<Poi> collectedPois = new ArrayList<>();
        for (BoundingBox box : CITY_BOUNDING_BOXES) {
            collectedPois.addAll(convergeDatasetService.getDataSet(box.minLon(), box.minLat(), box.maxLon(), box.maxLat()));
        }
        List<MergedPoi> mergedPois = convergeDatasetService.filterDataset(collectedPois);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File(OUTPUT_PATH), mergedPois);

        return mergedPois;
    }

    public static void main(String[] args) throws IOException {
        ConvergeDatasetService convergeDatasetService = new ConvergeDatasetService(
                List.of(new BuildCityDatasetOvertureImpl(), new BuildCityDatasetOsmImpl())
        );
        PoiDatasetStartup startup = new PoiDatasetStartup(convergeDatasetService);
        List<MergedPoi> mergedPois = startup.load();
        System.out.println("Merged POI count: " + mergedPois.size());
    }
}
