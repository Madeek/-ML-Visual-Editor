package com.example.swingapp.model;

import java.awt.geom.Path2D;

/**
 * Represents a Region (polygon or rectangle) in the model.
 */
public class Region extends ReMoDeLEntity {
    public Region() {
        super();
        setType("Region");
    }

    public Region(String id) {
        super(id);
        setType("Region");
    }

    public Path2D getShapePath() {
        Object v = get("path");
        if (v instanceof Path2D) return (Path2D) v;
        return null;
    }

    public void setShapePath(Path2D p) {
        put("path", p);
    }
}
