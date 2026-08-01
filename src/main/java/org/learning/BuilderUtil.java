package org.learning;

import java.text.Normalizer;
import java.util.Locale;

public class BuilderUtil {

    public static String normalize(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        String withoutDiacritics = decomposed.replaceAll("\\p{M}", "");
        String withoutPunctuation = withoutDiacritics.replaceAll("[^a-zA-Z0-9\\s]", "");
        return withoutPunctuation.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
