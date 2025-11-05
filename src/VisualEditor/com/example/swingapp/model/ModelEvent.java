package com.example.swingapp.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class ModelEvent implements Serializable {
    public enum Type { ENTITY_ADDED, ENTITY_UPDATED, ENTITY_REMOVED, BATCH, RESET }

    private final Type type;
    private final List<String> entityIds;

    public ModelEvent(Type type, List<String> ids) {
        this.type = type;
        this.entityIds = ids == null ? Collections.emptyList() : Collections.unmodifiableList(ids);
    }

    public Type getType() {
        return type;
    }

    public List<String> getEntityIds() {
        return entityIds;
    }
}
