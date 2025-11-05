package com.example.swingapp.model;

/**
 * Represents a Concept in the ReMoDeL domain.
 */
public class Concept extends ReMoDeLEntity {
    public Concept() {
        super();
        setType("Concept");
    }

    public Concept(String id) {
        super(id);
        setType("Concept");
    }

    public String getLabel() {
        Object v = get("label");
        return v == null ? null : v.toString();
    }

    public void setLabel(String label) {
        put("label", label);
    }
}
