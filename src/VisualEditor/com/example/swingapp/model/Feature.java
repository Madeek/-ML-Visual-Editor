package com.example.swingapp.model;

/**
 * Represents a Feature attached to a Concept or other entity.
 */
public class Feature extends ReMoDeLEntity {
    public Feature() {
        super();
        setType("Feature");
    }

    public Feature(String id) {
        super(id);
        setType("Feature");
    }

    public Object getValue() {
        return get("value");
    }

    public void setValue(Object v) {
        put("value", v);
    }
}
