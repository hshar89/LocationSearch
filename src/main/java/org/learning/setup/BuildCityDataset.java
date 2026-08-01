package org.learning.setup;

import org.learning.exception.BuildDatasetException;
import org.learning.model.Poi;

import java.util.List;
import java.util.Set;

public interface BuildCityDataset {

    static final Set<String> USABLE_CATEGORIES = Set.of(
            // food & drink
            "restaurant", "fast_food_restaurant", "pizza_restaurant", "italian_restaurant",
            "chinese_restaurant", "mexican_restaurant", "american_restaurant", "cafe",
            "coffee_shop", "bar", "bakery", "delicatessen", "ice_cream_shop", "brewery",
            "night_club", "wine_bar", "pub",
            // parks & recreation
            "park", "playground", "garden", "botanical_garden", "zoo", "aquarium", "beach",
            "hiking_trail", "national_park", "nature_reserve", "dog_park",
            // culture & landmarks
            "museum", "art_gallery", "landmark_and_historical_building", "monument",
            "historical_landmark", "theater", "performing_arts_theater", "concert_hall",
            "library", "public_library", "cultural_center",
            // transit
            "train_station", "subway_station", "bus_station", "airport", "ferry_terminal",
            "transportation", "parking",
            // religious sites
            "church_cathedral", "religious_organization", "mosque", "synagogue", "temple",
            // accommodation
            "hotel", "motel", "resort", "accommodation", "bed_and_breakfast",
            // entertainment & sport
            "stadium", "arena", "amusement_park", "movie_theater", "bowling_alley",
            "golf_course", "aquatic_center", "sports_complex",
            // education
            "university", "college",
            // government & civic
            "city_hall", "courthouse", "post_office", "fire_station", "police_station",
            "government_office", "community_center", "convention_center"
    );
    List<Poi> fetchNamedPois(double lon1, double lat1, double lon2, double lat2) throws BuildDatasetException;
}
