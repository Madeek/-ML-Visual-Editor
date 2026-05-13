package com.example.swingapp.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/**
 * Serializable base entity used by the in-memory model and persistence layer.
 */
public class ReMoDeLEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String type;
    private Map<String, Object> properties = new HashMap<>();

    public ReMoDeLEntity() {
        this.id = UUID.randomUUID().toString();
    }

    public ReMoDeLEntity(String id) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new HashMap<>() : properties;
    }

    public Object get(String key) {
        return properties.get(key);
    }

    public void put(String key, Object value) {
        properties.put(key, value);
    }

    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReMoDeLEntity)) return false;
        ReMoDeLEntity that = (ReMoDeLEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ReMoDeLEntity{" + "id='" + id + '\'' + ", type='" + type + '\'' + '}';
    }


    /**
     * Creates a shallow copy with a cloned property map.
     */
    public ReMoDeLEntity copy() {
        ReMoDeLEntity r = new ReMoDeLEntity(this.id);
        r.setType(this.type);
        r.setProperties(new HashMap<>(this.properties));
        return r;
    }
}
