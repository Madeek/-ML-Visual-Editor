package com.example.swingapp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory manager for ReMoDeL entities.
 * Emits ModelEvent to registered listeners when mutations occur.
 */
public class ReMoDeLModel {
    private final Map<String, ReMoDeLEntity> entities = new ConcurrentHashMap<>();
    private final List<ModelListener> listeners = new CopyOnWriteArrayList<>();

    // batch mode
    private final List<ModelEvent> pending = new ArrayList<>();
    private boolean inBatch = false;

    public void addListener(ModelListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(ModelListener l) {
        listeners.remove(l);
    }

    public ReMoDeLEntity get(String id) {
        return entities.get(id);
    }

    public List<ReMoDeLEntity> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entities.values()));
    }

    public void addEntity(ReMoDeLEntity e) {
        if (e == null) return;
        entities.put(e.getId(), e);
        dispatch(new ModelEvent(ModelEvent.Type.ENTITY_ADDED, List.of(e.getId())));
    }

    public void updateEntity(ReMoDeLEntity e) {
        if (e == null) return;
        entities.put(e.getId(), e);
        dispatch(new ModelEvent(ModelEvent.Type.ENTITY_UPDATED, List.of(e.getId())));
    }

    public ReMoDeLEntity removeEntity(String id) {
        ReMoDeLEntity old = entities.remove(id);
        if (old != null) dispatch(new ModelEvent(ModelEvent.Type.ENTITY_REMOVED, List.of(id)));
        return old;
    }

    public void beginBatch() {
        inBatch = true;
        pending.clear();
    }

    public void endBatch() {
        inBatch = false;
        if (!pending.isEmpty()) {
            // collapse to a single BATCH event (listeners can refresh as they need)
            List<String> ids = new ArrayList<>();
            for (ModelEvent me : pending) ids.addAll(me.getEntityIds());
            dispatch(new ModelEvent(ModelEvent.Type.BATCH, ids));
            pending.clear();
        }
    }

    private void dispatch(ModelEvent e) {
        if (inBatch) {
            pending.add(e);
            return;
        }
        for (ModelListener l : listeners) {
            try {
                l.modelChanged(e);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
