package org.learning.setup;

import org.learning.exception.BuildDatasetException;
import org.learning.model.Poi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BuildCityDatasetOsmImpl implements BuildCityDataset {

    // Regional Geofabrik extracts covering the bboxes of all cities queried
    // (NYC, DC, SF); state lines don't align with any of them, so NY+NJ are
    // both scanned for the NYC bbox.
    private static final List<String> PBF_PATHS = List.of(
            "data/osm/new-york-latest.osm.pbf",
            "data/osm/new-jersey-latest.osm.pbf",
            "data/osm/district-of-columbia-latest.osm.pbf",
            "data/osm/norcal-latest.osm.pbf"
    );

    // OSM has no single category tag; amenity/shop/tourism/leisure/railway/
    // aeroway/historic are separate keys. These maps translate the tag values
    // we care about into BuildCityDataset.USABLE_CATEGORIES strings, checked
    // in priority order (first matching key wins).
    private static final Map<String, String> AMENITY_CATEGORIES = Map.ofEntries(
            Map.entry("restaurant", "restaurant"),
            Map.entry("fast_food", "fast_food_restaurant"),
            Map.entry("cafe", "cafe"),
            Map.entry("bar", "bar"),
            Map.entry("pub", "pub"),
            Map.entry("nightclub", "night_club"),
            Map.entry("ice_cream", "ice_cream_shop"),
            Map.entry("library", "library"),
            Map.entry("university", "university"),
            Map.entry("college", "college"),
            Map.entry("fire_station", "fire_station"),
            Map.entry("police", "police_station"),
            Map.entry("post_office", "post_office"),
            Map.entry("townhall", "city_hall"),
            Map.entry("courthouse", "courthouse"),
            Map.entry("community_centre", "community_center"),
            Map.entry("parking", "parking"),
            Map.entry("bus_station", "bus_station"),
            Map.entry("ferry_terminal", "ferry_terminal"),
            Map.entry("conference_centre", "convention_center"),
            Map.entry("theatre", "theater"),
            Map.entry("cinema", "movie_theater")
    );

    private static final Map<String, String> SHOP_CATEGORIES = Map.of(
            "bakery", "bakery",
            "deli", "delicatessen",
            "wine", "wine_bar"
    );

    private static final Map<String, String> TOURISM_CATEGORIES = Map.ofEntries(
            Map.entry("museum", "museum"),
            Map.entry("gallery", "art_gallery"),
            Map.entry("hotel", "hotel"),
            Map.entry("motel", "motel"),
            Map.entry("resort", "resort"),
            Map.entry("guest_house", "bed_and_breakfast"),
            Map.entry("zoo", "zoo"),
            Map.entry("aquarium", "aquarium"),
            Map.entry("theme_park", "amusement_park"),
            Map.entry("attraction", "landmark_and_historical_building")
    );

    private static final Map<String, String> LEISURE_CATEGORIES = Map.ofEntries(
            Map.entry("park", "park"),
            Map.entry("playground", "playground"),
            Map.entry("garden", "garden"),
            Map.entry("dog_park", "dog_park"),
            Map.entry("golf_course", "golf_course"),
            Map.entry("stadium", "stadium"),
            Map.entry("sports_centre", "sports_complex"),
            Map.entry("nature_reserve", "nature_reserve"),
            Map.entry("bowling_alley", "bowling_alley")
    );

    private static final Map<String, String> RELIGION_CATEGORIES = Map.of(
            "christian", "church_cathedral",
            "muslim", "mosque",
            "jewish", "synagogue",
            "buddhist", "temple",
            "hindu", "temple"
    );

    // Aliases live in these unsuffixed tags. Localized variants use a
    // "tagname:lang" suffix (e.g. alt_name:vi) and are excluded by only
    // reading the bare tag names below.
    private static final List<String> ALIAS_TAGS = List.of("official_name", "short_name", "alt_name");

    @Override
    public List<Poi> fetchNamedPois(double lon1, double lat1, double lon2, double lat2) throws BuildDatasetException {
        double minLon = Math.min(lon1, lon2);
        double maxLon = Math.max(lon1, lon2);
        double minLat = Math.min(lat1, lat2);
        double maxLat = Math.max(lat1, lat2);

        List<Poi> pois = new ArrayList<>();
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement setup = conn.createStatement()) {
                    setup.execute("INSTALL spatial");
                    setup.execute("LOAD spatial");
                }

                // Nodes carry lat/lon directly; ways/relations resolve to
                // coordinates only via their member refs, so this scopes to
                // point POIs (node) rather than resolving building outlines.
                String sql = """
                        SELECT lat, lon, tags
                        FROM ST_ReadOSM(?)
                        WHERE kind = 'node'
                          AND tags['name'] IS NOT NULL
                          AND trim(tags['name']) <> ''
                          AND lat BETWEEN ? AND ?
                          AND lon BETWEEN ? AND ?
                        """;

                for (String pbfPath : PBF_PATHS) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, pbfPath);
                        ps.setDouble(2, minLat);
                        ps.setDouble(3, maxLat);
                        ps.setDouble(4, minLon);
                        ps.setDouble(5, maxLon);

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                @SuppressWarnings("unchecked")
                                Map<String, String> tags = (Map<String, String>) rs.getObject("tags");

                                String category = deriveCategory(tags);
                                if (category == null) {
                                    continue;
                                }

                                String name = tags.get("name");
                                pois.add(new Poi(
                                        name,
                                        name,
                                        deriveAliases(tags, name),
                                        category,
                                        1.0,
                                        rs.getDouble("lat"),
                                        rs.getDouble("lon"),
                                        "OSM",
                                        1,
                                        hasWikiEntry(tags),
                                        hasWebPresence(tags)
                                ));
                            }
                        }
                    }
                }
            }
        } catch (SQLException exception) {
            throw new BuildDatasetException(exception);
        }
        return pois;
    }

    private static String deriveCategory(Map<String, String> tags) {
        String amenity = tags.get("amenity");
        if ("place_of_worship".equals(amenity)) {
            String religion = tags.get("religion");
            return religion == null ? "religious_organization"
                    : RELIGION_CATEGORIES.getOrDefault(religion, "religious_organization");
        }
        if (amenity != null && AMENITY_CATEGORIES.containsKey(amenity)) {
            return AMENITY_CATEGORIES.get(amenity);
        }

        String shop = tags.get("shop");
        if (shop != null && SHOP_CATEGORIES.containsKey(shop)) {
            return SHOP_CATEGORIES.get(shop);
        }

        String tourism = tags.get("tourism");
        if (tourism != null && TOURISM_CATEGORIES.containsKey(tourism)) {
            return TOURISM_CATEGORIES.get(tourism);
        }

        String leisure = tags.get("leisure");
        if (leisure != null && LEISURE_CATEGORIES.containsKey(leisure)) {
            return LEISURE_CATEGORIES.get(leisure);
        }

        String railway = tags.get("railway");
        if ("station".equals(railway) || "subway_entrance".equals(railway)) {
            boolean isSubway = "subway".equals(tags.get("station")) || "yes".equals(tags.get("subway"));
            return isSubway ? "subway_station" : "train_station";
        }

        if ("aerodrome".equals(tags.get("aeroway"))) {
            return "airport";
        }

        String historic = tags.get("historic");
        if (historic != null) {
            return "monument".equals(historic) ? "monument" : "landmark_and_historical_building";
        }

        return null;
    }

    private static List<String> deriveAliases(Map<String, String> tags, String primaryName) {
        List<String> aliases = new ArrayList<>();
        for (String tag : ALIAS_TAGS) {
            String value = tags.get(tag);
            if (value == null) {
                continue;
            }
            for (String alias : value.split(";")) {
                String trimmed = alias.trim();
                if (!trimmed.isBlank() && !trimmed.equals(primaryName)) {
                    aliases.add(trimmed);
                }
            }
        }
        return aliases;
    }

    // A wikipedia/wikidata tag means someone considered this place notable
    // enough for its own encyclopedia entry.
    private static boolean hasWikiEntry(Map<String, String> tags) {
        return tags.get("wikipedia") != null || tags.get("wikidata") != null;
    }

    private static boolean hasWebPresence(Map<String, String> tags) {
        return tags.get("website") != null
                || tags.get("contact:website") != null
                || tags.get("contact:facebook") != null
                || tags.get("contact:instagram") != null;
    }
}
