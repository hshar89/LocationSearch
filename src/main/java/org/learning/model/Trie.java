package org.learning.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Trie {
    private Character character;
    private Map<Character, Trie> children;
    private List<String> placeIds;
    // Ranking scores aligned 1:1 with placeIds (same index), highest first. Populated by
    // TrieBuilder.rerankTrie so the published snapshot can carry the blended behavioral score.
    private List<Double> scores;
    private boolean terminal;

    public Trie(){
        this(null);
    }

    public Trie(Character ch){
        this.character = ch;
        this.children = new HashMap<>();
        this.placeIds = new ArrayList<>();
        this.scores = new ArrayList<>();
        this.terminal = false;
    }

    public Character getCharacter() {
        return character;
    }

    public void setCharacter(Character character) {
        this.character = character;
    }

    public Map<Character, Trie> getChildren() {
        return children;
    }

    public void setChildren(Map<Character, Trie> children) {
        this.children = children;
    }

    public List<String> getPlaceIds() {
        return placeIds;
    }

    public void setPlaceIds(List<String> placeIds) {
        this.placeIds = placeIds;
    }

    public List<Double> getScores() {
        return scores;
    }

    public void setScores(List<Double> scores) {
        this.scores = scores;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }
}
