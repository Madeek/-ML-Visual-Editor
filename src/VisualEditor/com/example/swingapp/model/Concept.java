package com.example.swingapp.model;


/**
 * Domain entity representing a concept node in the visual model.
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
