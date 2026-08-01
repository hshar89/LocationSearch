package org.learning.setup;

import org.duckdb.DuckDBArray;
import org.duckdb.DuckDBStruct;
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
import java.util.Set;

public class BuildCityDatasetOvertureImpl implements BuildCityDataset {

    private static final String OVERTURE_RELEASE = "2026-07-22.0";
    private static final String PLACES_PATH =
            "s3://overturemaps-us-west-2/release/" + OVERTURE_RELEASE
                    + "/theme=places/type=place/*";

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
                    setup.execute("INSTALL httpfs");
                    setup.execute("LOAD httpfs");
                    setup.execute("INSTALL spatial");
                    setup.execute("LOAD spatial");
                    setup.execute("SET s3_region='us-west-2'");
                }

                String categoryList = USABLE_CATEGORIES.stream()
                        .map(c -> "'" + c + "'")
                        .collect(java.util.stream.Collectors.joining(", "));

                String sql = """
                        SELECT names.primary AS name,
                               names.rules AS name_rules,
                               categories.primary AS category,
                               confidence,
                               bbox.xmin AS lon,
                               bbox.ymin AS lat,
                               sources,
                               websites,
                               socials,
                               brand.wikidata AS brand_wikidata
                        FROM read_parquet('%s', hive_partitioning=1)
                        WHERE names.primary IS NOT NULL
                          AND trim(names.primary) <> ''
                          AND categories.primary IN (%s)
                          AND bbox.xmin >= ? AND bbox.xmax <= ?
                          AND bbox.ymin >= ? AND bbox.ymax <= ?
                        """.formatted(PLACES_PATH, categoryList);

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setDouble(1, minLon);
                    ps.setDouble(2, maxLon);
                    ps.setDouble(3, minLat);
                    ps.setDouble(4, maxLat);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            pois.add(new Poi(
                                    rs.getString("name"),
                                    rs.getString("name"),
                                    extractEnglishAliases(rs.getObject("name_rules")),
                                    rs.getString("category"),
                                    rs.getDouble("confidence"),
                                    rs.getDouble("lat"),
                                    rs.getDouble("lon"),
                                    "OVERTURE",
                                    countSources(rs.getObject("sources")),
                                    rs.getString("brand_wikidata") != null,
                                    rs.getObject("websites") != null || rs.getObject("socials") != null
                            ));
                        }
                    }
                }
            }
        } catch (SQLException exception){
            throw new BuildDatasetException(exception);
        }
        return pois;
    }

    // names.rules is a list of name-variant structs (short/alternate/official
    // name, etc). language is unset for the vast majority of English entries
    // in this dataset, so treat null and "en" as English. A single rule's
    // value can bundle multiple aliases separated by ';'.
    private static List<String> extractEnglishAliases(Object nameRules) throws SQLException {
        if (nameRules == null) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        Object[] rules = (Object[]) ((DuckDBArray) nameRules).getArray();
        for (Object rule : rules) {
            Map<String, Object> attrs = ((DuckDBStruct) rule).getMap();
            Object language = attrs.get("language");
            if (language != null && !"en".equals(language)) {
                continue;
            }
            Object value = attrs.get("value");
            if (value == null) {
                continue;
            }
            for (String alias : ((String) value).split(";")) {
                if (!alias.isBlank()) {
                    aliases.add(alias.trim());
                }
            }
        }
        return aliases;
    }

    // sources is Overture's list of upstream provider contributions (Meta,
    // Foursquare, Microsoft, Overture-signals, etc) backing this place. More
    // independent contributions is a weak signal that the place is real/known.
    private static int countSources(Object sources) throws SQLException {
        if (sources == null) {
            return 0;
        }
        return ((Object[]) ((DuckDBArray) sources).getArray()).length;
    }
}
