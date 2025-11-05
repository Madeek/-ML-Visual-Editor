package com.example.swingapp.model;

/**
 * Represents a Connective linking two entities (e.g. concept->concept)
 */
public class Connective extends ReMoDeLEntity {
    public Connective() {
        super();
        setType("Connective");
    }

    public Connective(String id) {
        super(id);
        setType("Connective");
    }

    public String getFromId() {
        Object v = get("from");
        return v == null ? null : v.toString();
    }

    public void setFromId(String id) {
        put("from", id);
    }

    public String getToId() {
        Object v = get("to");
        return v == null ? null : v.toString();
    }

    public void setToId(String id) {
        put("to", id);
    }
}
