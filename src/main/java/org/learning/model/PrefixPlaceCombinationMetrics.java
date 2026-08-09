package org.learning.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrefixPlaceCombinationMetrics {
    private String prefix;
    private String placeId;
    private double sumReciprocalRank;
    private long impressions;
    private long selections;
    private long sumAtSelectionRank;
    private long abandonmentCounter;
}
