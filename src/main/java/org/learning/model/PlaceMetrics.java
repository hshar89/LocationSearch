package org.learning.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceMetrics {
    private String placeId;
    private long sumPrefixLenOnFirstAppearance; // prefix len when place first appeared per session
    private long countFirstAppearances;
    private long sumPrefixLenOnFirstTop;        // prefix len when place first hit rank 0 per session
    private long countFirstTop;
    private long sumPrefixLenOnSelection;       // prefix len for each prefix that contributed to a selection
    private long countSelections;
}
