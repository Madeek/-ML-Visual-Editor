package com.example.swingapp.persistence;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.swingapp.model.ReMoDeLEntity;
import com.example.swingapp.model.ReMoDeLModel;

/**
 * Exports ReMoDeL model to XML format suitable for ReMoDeL Model-Driven Engineering toolkit.
 */
public class ReMoDeLExporter {

    public enum ModelKind {
        TASK_MODEL("TaskModel"),
        IMPACT_MODEL("ImpactModel"),
        OBJECT_MODEL("ObjectModel"),
        STATE_MODEL("StateModel"),
        PROCESS_MODEL("ProcessModel");

        private final String dslName;

        ModelKind(String dslName) {
            this.dslName = dslName;
        }

        public String dslName() {
            return dslName;
        }
    }

    private static final Set<String> METAMODEL_NODE_TYPES = Set.of("OBJECT_TYPE", "RECTANGLE");

    private static class ConceptExport {
        final String id;
        final String name;
        final List<String> bodyLines = new ArrayList<>();
        final List<String> inheritTypes = new ArrayList<>();

        ConceptExport(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * Export model to XML file
     */
    public static void exportToXML(ReMoDeLModel model, String filePath) throws IOException {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<remodelDocument>\n");
        xml.append("  <entities>\n");

        // Export all entities (nodes)
        for (ReMoDeLEntity entity : model.getAll()) {
            if (!isConnectorEntity(entity)) {
                xml.append(entityToXML(entity, 4));
            }
        }
        xml.append("  </entities>\n");
        xml.append("  <connectors>\n");

        // Export connectors (arcs)
        for (ReMoDeLEntity entity : model.getAll()) {
            if (isConnectorEntity(entity)) {
                xml.append(connectorToXML(entity, 4));
            }
        }
        xml.append("  </connectors>\n");
        xml.append("</remodelDocument>\n");

        // Write to file
        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(xml.toString());
        }
    }

    /**
     * Export to JSON format
     */
    public static void exportToJSON(ReMoDeLModel model, String filePath) throws IOException {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"entities\": [\n");

        List<ReMoDeLEntity> allEntities = model.getAll();
        List<ReMoDeLEntity> nodeEntities = allEntities.stream()
            .filter(e -> !isConnectorEntity(e))
            .collect(Collectors.toList());
        
        for (int i = 0; i < nodeEntities.size(); i++) {
            json.append(entityToJSON(nodeEntities.get(i), 4));
            if (i < nodeEntities.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"connectors\": [\n");

        List<ReMoDeLEntity> connectorEntities = allEntities.stream()
            .filter(ReMoDeLExporter::isConnectorEntity)
            .collect(Collectors.toList());
        
        for (int i = 0; i < connectorEntities.size(); i++) {
            json.append(connectorToJSON(connectorEntities.get(i), 4));
            if (i < connectorEntities.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(json.toString());
        }
    }

    /**
     * Export to ReMoDeL model-instance DSL (.mod text).
     */
    public static void exportToRemodelModel(ReMoDeLModel model, String filePath, ModelKind modelKind) throws IOException {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");

        ModelKind kind = modelKind == null ? ModelKind.TASK_MODEL : modelKind;

        List<ReMoDeLEntity> nodes = model.getAll().stream()
            .filter(e -> !isConnectorEntity(e))
            .sorted(ReMoDeLExporter::compareForExport)
            .collect(Collectors.toList());

        List<ReMoDeLEntity> connectors = model.getAll().stream()
            .filter(ReMoDeLExporter::isConnectorEntity)
            .sorted(ReMoDeLExporter::compareForExport)
            .collect(Collectors.toList());

        String diagramName = inferDiagramName(kind, nodes);
        String modelName = inferModelName(kind, diagramName, filePath);

        String out;
        switch (kind) {
            case STATE_MODEL:
                out = buildStateModelDsl(modelName, diagramName, nodes, connectors, filePath);
                break;
            case OBJECT_MODEL:
                out = buildObjectModelDsl(modelName, diagramName, nodes, connectors, filePath);
                break;
            case IMPACT_MODEL:
                out = buildImpactModelDsl(modelName, diagramName, nodes, connectors, filePath);
                break;
            case PROCESS_MODEL:
            case TASK_MODEL:
            default:
                out = buildStandardDiagramDsl(kind, modelName, diagramName, nodes, connectors, filePath);
                break;
        }

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(out);
        }
    }

    /**
     * Legacy entry point retained for existing callers.
     */
    public static void exportToRemodel(ReMoDeLModel model, String filePath) throws IOException {
        exportToRemodelModel(model, filePath, ModelKind.TASK_MODEL);
    }

    private static String buildStandardDiagramDsl(
            ModelKind kind,
            String modelName,
            String diagramName,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            String filePath) {

        Map<String, String> nodeTokenById = new java.util.LinkedHashMap<>();
        Map<String, Integer> prefixCounters = new java.util.LinkedHashMap<>();
        List<ModCollection> collections = buildCollections(kind, nodes, connectors, nodeTokenById, prefixCounters);

        StringBuilder out = new StringBuilder();
        appendCreatedByComment(out, filePath);
        out.append("model ").append(modelName).append(" : ").append(kind.dslName()).append(" {\n");
        out.append("   d1 : Diagram(name = \"").append(escapeDsl(diagramName)).append("\"");

        for (ModCollection collection : collections) {
            out.append(",\n");
            out.append("      ").append(collection.propertyName).append(" = ")
               .append(collection.elementType).append("[");
            if (!collection.entries.isEmpty()) {
                out.append("\n");
                for (int i = 0; i < collection.entries.size(); i++) {
                    out.append("         ").append(collection.entries.get(i));
                    if (i < collection.entries.size() - 1) out.append(",");
                    out.append("\n");
                }
                out.append("      ");
            }
            out.append("]");
        }
        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static String buildImpactModelDsl(
            String modelName,
            String diagramName,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            String filePath) {

        Map<String, String> nodeTokenById = new java.util.LinkedHashMap<>();
        Map<String, Integer> prefixCounters = new java.util.LinkedHashMap<>();
        List<ModCollection> collections = buildCollections(ModelKind.IMPACT_MODEL, nodes, connectors, nodeTokenById, prefixCounters);

        for (ModCollection c : collections) {
            if (!"impacts".equals(c.propertyName)) continue;
            for (int i = 0; i < c.entries.size(); i++) {
                String entry = c.entries.get(i);
                if (!entry.contains("kind = ")) {
                    c.entries.set(i, entry.replace(")", ", kind = \"read\")"));
                }
            }
        }

        StringBuilder out = new StringBuilder();
        appendCreatedByComment(out, filePath);
        out.append("model ").append(modelName).append(" : ImpactModel {\n");
        out.append("   d1 : Diagram(name = \"").append(escapeDsl(diagramName)).append("\"");

        for (ModCollection collection : collections) {
            out.append(",\n");
            out.append("      ").append(collection.propertyName).append(" = ")
               .append(collection.elementType).append("[");
            if (!collection.entries.isEmpty()) {
                out.append("\n");
                for (int i = 0; i < collection.entries.size(); i++) {
                    out.append("         ").append(collection.entries.get(i));
                    if (i < collection.entries.size() - 1) out.append(",");
                    out.append("\n");
                }
                out.append("      ");
            }
            out.append("]");
        }

        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static String buildObjectModelDsl(
            String modelName,
            String diagramName,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            String filePath) {

        Map<String, Integer> counters = new java.util.LinkedHashMap<>();
        Map<String, String> objectTokenById = new java.util.LinkedHashMap<>();

        List<ReMoDeLEntity> objectNodes = nodes.stream()
            .filter(n -> {
                String shape = getShapeType(n);
                return "OBJECT_TYPE".equals(shape) || "RECTANGLE".equals(shape);
            })
            .collect(Collectors.toList());

        List<String> objectEntries = new ArrayList<>();
        List<String> propertyEntries = new ArrayList<>();
        for (ReMoDeLEntity node : objectNodes) {
            String token = nextToken(counters, "o");
            objectTokenById.put(node.getId(), token);
            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) name = "ObjectType " + token.substring(1);

            String text = asText(node.get("text"));
            String[] lines = text == null ? new String[0] : text.split("\\R");
            List<String> localProperties = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String raw = lines[i] == null ? "" : lines[i].trim();
                if (raw.isBlank()) continue;
                boolean id = raw.startsWith("*");
                if (id) raw = raw.substring(1).trim();

                String attrName;
                String attrType;
                if (raw.contains(":")) {
                    String[] parts = raw.split(":", 2);
                    attrName = sanitizeMemberIdentifier(parts[0]);
                    attrType = normalizeTypeToken(parts[1]);
                } else {
                    attrName = sanitizeMemberIdentifier(raw);
                    attrType = "b5";
                }
                if (attrName.isBlank()) continue;

                String propToken = nextToken(counters, "a");
                StringBuilder property = new StringBuilder();
                property.append(propToken).append(" : Attribute(name = \"").append(escapeDsl(attrName)).append("\"");
                if (id) property.append(", id = true");
                property.append(", type = ").append(attrType).append(")");
                localProperties.add(property.toString());
                propertyEntries.add(property.toString());
            }

            StringBuilder object = new StringBuilder();
            object.append(token).append(" : ObjectType(name = \"").append(escapeDsl(name)).append("\"");
            if (!localProperties.isEmpty()) {
                object.append(", properties = Property[\n");
                for (int i = 0; i < localProperties.size(); i++) {
                    object.append("         ").append(localProperties.get(i));
                    if (i < localProperties.size() - 1) object.append(",");
                    object.append("\n");
                }
                object.append("      ]");
            }
            object.append(")");
            objectEntries.add(object.toString());
        }

        List<String> referenceEntries = new ArrayList<>();
        List<String> generalisationEntries = new ArrayList<>();
        for (ReMoDeLEntity connector : connectors) {
            String shape = getShapeType(connector);
            String sourceId = toStringOrNull(connector.get("fromId"));
            if (sourceId == null) sourceId = toStringOrNull(connector.get("from"));
            String targetId = toStringOrNull(connector.get("toId"));
            if (targetId == null) targetId = toStringOrNull(connector.get("to"));
            String sourceToken = objectTokenById.get(sourceId);
            String targetToken = objectTokenById.get(targetId);
            if (sourceToken == null || targetToken == null) continue;

            if ("REFERENCE".equals(shape)) {
                String token = nextToken(counters, "r");
                String name = inferReferenceName(asText(connector.get("text")), targetToken);
                referenceEntries.add(token + " : Reference(name = \"" + escapeDsl(name)
                    + "\", type = " + targetToken + ")");
            } else if ("ARROW_EMPTY".equals(shape)) {
                String token = nextToken(counters, "g");
                generalisationEntries.add(token + " : Generalisation(source = " + sourceToken + ", target = " + targetToken + ")");
            }
        }

        StringBuilder out = new StringBuilder();
        appendCreatedByComment(out, filePath);
        out.append("model ").append(modelName).append(" : ObjectModel {\n");
        out.append("   d1 : Diagram(name = \"").append(escapeDsl(diagramName)).append("\", basicTypes = BasicType[\n");
        out.append("      b1 : BasicType(name = \"Boolean\"),\n");
        out.append("      b2 : BasicType(name = \"Integer\"),\n");
        out.append("      b3 : BasicType(name = \"Natural\"),\n");
        out.append("      b4 : BasicType(name = \"Real\"),\n");
        out.append("      b5 : BasicType(name = \"String\"),\n");
        out.append("      b6 : BasicType(name = \"Date\")\n");
        out.append("   ]");

        if (!objectEntries.isEmpty()) {
            out.append(", objectTypes = ObjectType[\n");
            for (int i = 0; i < objectEntries.size(); i++) {
                out.append("      ").append(objectEntries.get(i));
                if (i < objectEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }
        if (!referenceEntries.isEmpty()) {
            out.append(", references = Reference[\n");
            for (int i = 0; i < referenceEntries.size(); i++) {
                out.append("      ").append(referenceEntries.get(i));
                if (i < referenceEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }
        if (!generalisationEntries.isEmpty()) {
            out.append(", generalisations = Generalisation[\n");
            for (int i = 0; i < generalisationEntries.size(); i++) {
                out.append("      ").append(generalisationEntries.get(i));
                if (i < generalisationEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }

        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static String buildStateModelDsl(
            String modelName,
            String diagramName,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            String filePath) {

        Map<String, Integer> counters = new java.util.LinkedHashMap<>();
        Map<String, String> actorTokenById = new java.util.LinkedHashMap<>();
        Map<String, String> stateTokenById = new java.util.LinkedHashMap<>();

        List<String> actorEntries = new ArrayList<>();
        List<String> stateEntries = new ArrayList<>();

        for (ReMoDeLEntity node : nodes) {
            String shape = getShapeType(node);
            if (!"ACTOR".equals(shape)) continue;
            String token = nextToken(counters, "a");
            actorTokenById.put(node.getId(), token);
            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) name = "Actor " + token.substring(1);
            actorEntries.add(token + " : Actor(name = \"" + escapeDsl(name) + "\")");
        }

        Map<String, java.util.LinkedHashSet<String>> stateActors = new java.util.LinkedHashMap<>();
        for (ReMoDeLEntity connector : connectors) {
            if (!"AUTHORISATION".equals(getShapeType(connector))) continue;
            String fromId = toStringOrNull(connector.get("fromId"));
            if (fromId == null) fromId = toStringOrNull(connector.get("from"));
            String toId = toStringOrNull(connector.get("toId"));
            if (toId == null) toId = toStringOrNull(connector.get("to"));

            String actorToken = actorTokenById.get(fromId);
            String stateToken = stateTokenById.get(toId);
            if (actorToken == null || stateToken == null) continue;
            stateActors.computeIfAbsent(stateToken, k -> new java.util.LinkedHashSet<>()).add(actorToken);
        }

        for (ReMoDeLEntity node : nodes) {
            String shape = getShapeType(node);
            if (!"STATE".equals(shape)) continue;
            String token = nextToken(counters, "s");
            stateTokenById.put(node.getId(), token);
        }

        // Re-run actor bindings once state tokens are known.
        stateActors.clear();
        for (ReMoDeLEntity connector : connectors) {
            if (!"AUTHORISATION".equals(getShapeType(connector))) continue;
            String fromId = toStringOrNull(connector.get("fromId"));
            if (fromId == null) fromId = toStringOrNull(connector.get("from"));
            String toId = toStringOrNull(connector.get("toId"));
            if (toId == null) toId = toStringOrNull(connector.get("to"));

            String actorToken = actorTokenById.get(fromId);
            String stateToken = stateTokenById.get(toId);
            if (actorToken == null || stateToken == null) continue;
            stateActors.computeIfAbsent(stateToken, k -> new java.util.LinkedHashSet<>()).add(actorToken);
        }

        for (ReMoDeLEntity node : nodes) {
            String shape = getShapeType(node);
            if (!"STATE".equals(shape)) continue;
            String token = stateTokenById.get(node.getId());
            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) name = "State " + token.substring(1);

            StringBuilder state = new StringBuilder();
            state.append(token).append(" : State(name = \"").append(escapeDsl(name)).append("\"");
            java.util.LinkedHashSet<String> actors = stateActors.get(token);
            if (actors != null && !actors.isEmpty()) {
                state.append(", actors = Actor[");
                int i = 0;
                for (String actorToken : actors) {
                    if (i++ > 0) state.append(", ");
                    state.append(actorToken);
                }
                state.append("]");
            }
            state.append(")");
            stateEntries.add(state.toString());
        }

        List<String> transitionEntries = new ArrayList<>();
        int eventCounter = 0;
        int actionCounter = actorEntries.size();
        int guardCounter = 0;

        for (ReMoDeLEntity connector : connectors) {
            String shape = getShapeType(connector);
            if (!"ARROW_OPEN".equals(shape)
                    && !"INITIAL_TRANSITION".equals(shape)
                    && !"FINAL_TRANSITION".equals(shape)) {
                continue;
            }

            String token = nextToken(counters, "t");
            String sourceId = toStringOrNull(connector.get("fromId"));
            if (sourceId == null) sourceId = toStringOrNull(connector.get("from"));
            String targetId = toStringOrNull(connector.get("toId"));
            if (targetId == null) targetId = toStringOrNull(connector.get("to"));
            String source = stateTokenById.get(sourceId);
            String target = stateTokenById.get(targetId);

            String[] textLines = splitLines(asText(connector.get("text")));
            String transitionName = textLines.length == 0 || textLines[0].isBlank()
                ? defaultTransitionName(shape)
                : textLines[0].trim();

            String guardText = null;
            String actionProc = null;
            for (int i = 1; i < textLines.length; i++) {
                String line = textLines[i] == null ? "" : textLines[i].trim();
                if (line.isBlank()) continue;
                if (guardText == null && (line.startsWith("[") || line.toLowerCase().startsWith("guard"))) {
                    guardText = line;
                    continue;
                }
                if (actionProc == null) {
                    actionProc = line;
                }
            }
            if (actionProc == null && shouldGenerateDefaultAction(transitionName, shape)) {
                actionProc = sanitizeIdentifier(transitionName);
            }

            StringBuilder transition = new StringBuilder();
            transition.append(token).append(" : Transition(name = \"").append(escapeDsl(transitionName)).append("\"");
            if (!"INITIAL_TRANSITION".equals(shape) && source != null) {
                transition.append(", source = ").append(source);
            }
            if (!"FINAL_TRANSITION".equals(shape) && target != null) {
                transition.append(", target = ").append(target);
            }

            String eventToken = "e" + (++eventCounter);
            transition.append(", event = \n");
            transition.append("         ").append(eventToken).append(" : Event(name = \"").append(escapeDsl(transitionName)).append("\")");

            if (guardText != null && !guardText.isBlank()) {
                String guardToken = "g" + (++guardCounter);
                transition.append(", guard = \n");
                transition.append("         ").append(guardToken).append(" : Guard(guard = \"").append(escapeDsl(guardText)).append("\")");
            }
            if (actionProc != null && !actionProc.isBlank()) {
                String actionToken = "a" + (++actionCounter);
                transition.append(", action = \n");
                transition.append("         ").append(actionToken).append(" : Action(proc = \"").append(escapeDsl(actionProc)).append("\")");
            }
            transition.append("\n      )");
            transitionEntries.add(transition.toString());
        }

        String machineName = diagramName.endsWith("Machine") ? diagramName : (diagramName + " Machine");
        StringBuilder out = new StringBuilder();
        appendCreatedByComment(out, filePath);
        out.append("model ").append(modelName).append(" : StateModel {\n");
        out.append("   m1 : Machine(name = \"").append(escapeDsl(machineName)).append("\"");

        if (!actorEntries.isEmpty()) {
            out.append(", actors = Actor[\n");
            for (int i = 0; i < actorEntries.size(); i++) {
                out.append("      ").append(actorEntries.get(i));
                if (i < actorEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }
        if (!stateEntries.isEmpty()) {
            out.append(", states = State[\n");
            for (int i = 0; i < stateEntries.size(); i++) {
                out.append("      ").append(stateEntries.get(i));
                if (i < stateEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }
        if (!transitionEntries.isEmpty()) {
            out.append(", transitions = Transition[\n");
            for (int i = 0; i < transitionEntries.size(); i++) {
                out.append("      ").append(transitionEntries.get(i));
                if (i < transitionEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }

        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendCreatedByComment(StringBuilder out, String filePath) {
        if (out == null) return;
        String safePath = filePath == null ? "" : filePath.replace('\\', '/');
        out.append("# Created by: VisualEditor ").append(safePath).append("\n\n");
    }

    private static String normalizeTypeToken(String rawType) {
        String normalized = rawType == null ? "" : rawType.trim();
        if (normalized.isBlank()) return "b5";
        String lower = normalized.toLowerCase();
        if ("boolean".equals(lower) || "bool".equals(lower)) return "b1";
        if ("integer".equals(lower) || "int".equals(lower)) return "b2";
        if ("natural".equals(lower) || "nat".equals(lower)) return "b3";
        if ("real".equals(lower) || "float".equals(lower) || "double".equals(lower)) return "b4";
        if ("date".equals(lower) || "datetime".equals(lower)) return "b6";
        if ("string".equals(lower) || "text".equals(lower) || "char".equals(lower)) return "b5";
        return "b5";
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isBlank()) return new String[0];
        return text.split("\\R");
    }

    private static String defaultTransitionName(String shapeType) {
        if ("INITIAL_TRANSITION".equals(shapeType)) return "entry";
        if ("FINAL_TRANSITION".equals(shapeType)) return "exit";
        return "transition";
    }

    private static boolean shouldGenerateDefaultAction(String transitionName, String shapeType) {
        if (!"ARROW_OPEN".equals(shapeType)) return false;
        if (transitionName == null) return false;
        String n = transitionName.trim().toLowerCase();
        return !n.equals("entry") && !n.equals("exit") && !n.equals("returnhome");
    }

    private static class ModCollection {
        final String propertyName;
        final String elementType;
        final List<String> entries = new ArrayList<>();

        ModCollection(String propertyName, String elementType) {
            this.propertyName = propertyName;
            this.elementType = elementType;
        }
    }

    private static class ModValue {
        final String value;
        final boolean raw;

        ModValue(String value, boolean raw) {
            this.value = value;
            this.raw = raw;
        }
    }

    private static boolean isConnectorEntity(ReMoDeLEntity e) {
        return e != null && "connector".equalsIgnoreCase(e.getType());
    }

    private static List<ModCollection> buildCollections(
            ModelKind kind,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters) {

        List<ModCollection> collections = new ArrayList<>();

        switch (kind) {
            case IMPACT_MODEL:
                collections.add(buildNodeCollection("tasks", "Task", nodes, nodeTokenById, prefixCounters, "t", "Task", "OVAL"));
                collections.add(buildNodeCollection("objects", "Object", nodes, nodeTokenById, prefixCounters, "o", "Object", "RECTANGLE", "OBJECT_TYPE"));
                collections.add(buildConnectorCollection("impacts", "Impact", connectors, nodeTokenById, prefixCounters, "i", "IMPACT", false, ReMoDeLExporter::extractImpactKind));
                collections.add(buildConnectorCollection("generalisations", "Generalisation", connectors, nodeTokenById, prefixCounters, "g", "ARROW_EMPTY", false, null));
                collections.add(buildConnectorCollection("compositions", "Composition", connectors, nodeTokenById, prefixCounters, "c", "ARROW_DIAMOND", false, null));
                break;
            case OBJECT_MODEL:
                collections.add(buildNodeCollection("objectTypes", "ObjectType", nodes, nodeTokenById, prefixCounters, "o", "ObjectType", "OBJECT_TYPE", "RECTANGLE"));
                collections.add(buildConnectorCollection("references", "Reference", connectors, nodeTokenById, prefixCounters, "r", "REFERENCE", false, ReMoDeLExporter::extractReferenceKind));
                collections.add(buildConnectorCollection("compositions", "Composition", connectors, nodeTokenById, prefixCounters, "c", "ARROW_DIAMOND", false, null));
                collections.add(buildConnectorCollection("generalisations", "Generalisation", connectors, nodeTokenById, prefixCounters, "g", "ARROW_EMPTY", false, null));
                break;
            case STATE_MODEL:
                collections.add(buildNodeCollection("states", "State", nodes, nodeTokenById, prefixCounters, "s", "State", "STATE"));
                collections.add(buildNodeCollection("actors", "Actor", nodes, nodeTokenById, prefixCounters, "a", "Actor", "ACTOR"));
                collections.add(buildTransitionCollection(connectors, nodeTokenById, prefixCounters));
                collections.add(buildConnectorCollection("authorisations", "Authorisation", connectors, nodeTokenById, prefixCounters, "au", "AUTHORISATION", false, null));
                break;
            case PROCESS_MODEL:
                collections.add(buildNodeCollection("processes", "Process", nodes, nodeTokenById, prefixCounters, "p", "Process", "ROUNDED_RECTANGLE"));
                collections.add(buildConnectorCollection("dataflows", "Dataflow", connectors, nodeTokenById, prefixCounters, "f", "ARROW_FILLED", false, null));
                break;
            case TASK_MODEL:
            default:
                collections.add(buildNodeCollection("actors", "Actor", nodes, nodeTokenById, prefixCounters, "a", "Actor", "ACTOR"));
                collections.add(buildNodeCollection("tasks", "Task", nodes, nodeTokenById, prefixCounters, "t", "Task", "OVAL", "SYSTEM", "BOUNDARY"));
                collections.add(buildConnectorCollection("associations", "Association", connectors, nodeTokenById, prefixCounters, "a", "LINE", false, null));
                collections.add(buildConnectorCollection("associations", "Association", connectors, nodeTokenById, prefixCounters, "a", "ENACTS", true, null));
                collections.add(buildConnectorCollection("compositions", "Composition", connectors, nodeTokenById, prefixCounters, "c", "ARROW_DIAMOND", false, null));
                collections.add(buildConnectorCollection("generalisations", "Generalisation", connectors, nodeTokenById, prefixCounters, "g", "ARROW_EMPTY", false, null));
                break;
        }

        return mergeCollectionsByProperty(collections);
    }

    private static List<ModCollection> mergeCollectionsByProperty(List<ModCollection> input) {
        List<ModCollection> merged = new ArrayList<>();
        Map<String, ModCollection> index = new java.util.LinkedHashMap<>();
        for (ModCollection c : input) {
            if (c == null || c.entries.isEmpty()) continue;
            ModCollection existing = index.get(c.propertyName);
            if (existing == null) {
                existing = new ModCollection(c.propertyName, c.elementType);
                index.put(c.propertyName, existing);
                merged.add(existing);
            }
            existing.entries.addAll(c.entries);
        }
        return merged;
    }

    private static ModCollection buildNodeCollection(
            String propertyName,
            String elementType,
            List<ReMoDeLEntity> nodes,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters,
            String idPrefix,
            String fallbackNamePrefix,
            String... shapeTypes) {

        Set<String> shapeSet = shapeTypes == null ? Set.of() : Set.of(shapeTypes);
        ModCollection collection = new ModCollection(propertyName, elementType);
        int unnamedCount = 0;
        for (ReMoDeLEntity node : nodes) {
            String shapeType = getShapeType(node);
            if (!shapeSet.contains(shapeType)) continue;

            String token = nextToken(prefixCounters, idPrefix);
            nodeTokenById.put(node.getId(), token);

            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) {
                unnamedCount++;
                name = fallbackNamePrefix + " " + unnamedCount;
            }

            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("name", new ModValue(name, false));
            collection.entries.add(buildEntry(token, elementType, fields));
        }
        return collection;
    }

    private interface ConnectorFieldDecorator {
        void decorate(ReMoDeLEntity connector, java.util.LinkedHashMap<String, ModValue> fields);
    }

    private static ModCollection buildConnectorCollection(
            String propertyName,
            String elementType,
            List<ReMoDeLEntity> connectors,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters,
            String idPrefix,
            String requiredShapeType,
            boolean markEnacts,
            ConnectorFieldDecorator decorator) {

        ModCollection collection = new ModCollection(propertyName, elementType);
        for (ReMoDeLEntity connector : connectors) {
            if (!requiredShapeType.equals(getShapeType(connector))) continue;

            String sourceId = toStringOrNull(connector.get("fromId"));
            if (sourceId == null) sourceId = toStringOrNull(connector.get("from"));
            String targetId = toStringOrNull(connector.get("toId"));
            if (targetId == null) targetId = toStringOrNull(connector.get("to"));
            if (sourceId == null || targetId == null) continue;

            String sourceToken = nodeTokenById.get(sourceId);
            String targetToken = nodeTokenById.get(targetId);
            if (sourceToken == null || targetToken == null) continue;

            String token = nextToken(prefixCounters, idPrefix);
            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("source", new ModValue(sourceToken, true));
            fields.put("target", new ModValue(targetToken, true));
            if (markEnacts) {
                fields.put("enacts", new ModValue("true", true));
            }
            if (decorator != null) {
                decorator.decorate(connector, fields);
            }

            collection.entries.add(buildEntry(token, elementType, fields));
        }
        return collection;
    }

    private static ModCollection buildTransitionCollection(
            List<ReMoDeLEntity> connectors,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters) {

        ModCollection collection = new ModCollection("transitions", "Transition");
        for (ReMoDeLEntity connector : connectors) {
            String shapeType = getShapeType(connector);
            if (!"ARROW_OPEN".equals(shapeType)
                    && !"INITIAL_TRANSITION".equals(shapeType)
                    && !"FINAL_TRANSITION".equals(shapeType)) {
                continue;
            }

            String sourceId = toStringOrNull(connector.get("fromId"));
            if (sourceId == null) sourceId = toStringOrNull(connector.get("from"));
            String targetId = toStringOrNull(connector.get("toId"));
            if (targetId == null) targetId = toStringOrNull(connector.get("to"));
            if (sourceId == null || targetId == null) continue;

            String sourceToken = nodeTokenById.get(sourceId);
            String targetToken = nodeTokenById.get(targetId);
            if (sourceToken == null || targetToken == null) continue;

            String token = nextToken(prefixCounters, "t");
            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("source", new ModValue(sourceToken, true));
            fields.put("target", new ModValue(targetToken, true));

            if ("INITIAL_TRANSITION".equals(shapeType)) {
                fields.put("kind", new ModValue("initial", false));
            } else if ("FINAL_TRANSITION".equals(shapeType)) {
                fields.put("kind", new ModValue("final", false));
            }

            String label = firstLine(asText(connector.get("text")));
            if (label != null && !label.isBlank()) {
                fields.put("name", new ModValue(label, false));
            }

            collection.entries.add(buildEntry(token, "Transition", fields));
        }
        return collection;
    }

    private static void extractReferenceKind(ReMoDeLEntity connector, java.util.LinkedHashMap<String, ModValue> fields) {
        String label = asText(connector.get("text"));
        String second = secondLine(label);
        if (second == null || second.isBlank()) return;

        String normalized = second.trim();
        if (normalized.startsWith("{")) normalized = normalized.substring(1);
        if (normalized.endsWith("}")) normalized = normalized.substring(0, normalized.length() - 1);
        normalized = normalized.trim();
        if (normalized.isBlank()) return;
        fields.put("kind", new ModValue(normalized, false));
    }

    private static void extractImpactKind(ReMoDeLEntity connector, java.util.LinkedHashMap<String, ModValue> fields) {
        String label = firstLine(asText(connector.get("text")));
        if (label == null || label.isBlank()) {
            fields.put("kind", new ModValue("read", false));
            return;
        }
        String lower = label.trim().toLowerCase();
        String kind;
        if (lower.contains("create")) kind = "create";
        else if (lower.contains("update")) kind = "update";
        else if (lower.contains("delete") || lower.contains("remove")) kind = "delete";
        else kind = "read";
        fields.put("kind", new ModValue(kind, false));
    }

    private static String buildEntry(String token, String elementType, java.util.LinkedHashMap<String, ModValue> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(token).append(" : ").append(elementType).append("(");
        int i = 0;
        for (Map.Entry<String, ModValue> entry : fields.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(entry.getKey()).append(" = ");
            ModValue v = entry.getValue();
            if (v == null || v.value == null) {
                sb.append("\"\"");
            } else if (v.raw) {
                sb.append(v.value);
            } else {
                sb.append("\"").append(escapeDsl(v.value)).append("\"");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private static String inferModelName(ModelKind kind, String diagramName, String filePath) {
        String fromFile = inferModelNameFromFilePath(filePath);
        if (fromFile != null && !fromFile.isBlank()) return fromFile;

        String base = sanitizeIdentifier(diagramName);
        if (base.isBlank()) base = kind.dslName();
        return lowerFirst(base);
    }

    private static String inferModelNameFromFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;

        String normalized = filePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (name.isBlank()) return null;

        if (name.toLowerCase().endsWith(".mod")) {
            name = name.substring(0, name.length() - 4);
        } else {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }

        name = name.trim();
        if (name.isBlank()) return null;

        // Keep exact file base when it's already a valid DSL identifier.
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) return name;

        String sanitized = sanitizeIdentifier(name);
        return sanitized.isBlank() ? null : sanitized;
    }

    private static String inferDiagramName(ModelKind kind, List<ReMoDeLEntity> nodes) {
        String preferredShape;
        switch (kind) {
            case TASK_MODEL:
                preferredShape = "SYSTEM";
                break;
            case PROCESS_MODEL:
                preferredShape = "ROUNDED_RECTANGLE";
                break;
            case STATE_MODEL:
                preferredShape = "STATE";
                break;
            case OBJECT_MODEL:
                preferredShape = "OBJECT_TYPE";
                break;
            case IMPACT_MODEL:
            default:
                preferredShape = "OVAL";
                break;
        }

        for (ReMoDeLEntity node : nodes) {
            if (!preferredShape.equals(getShapeType(node))) continue;
            String name = firstLine(asText(node.get("text")));
            if (name != null && !name.isBlank()) return name;
        }

        for (ReMoDeLEntity node : nodes) {
            String name = firstLine(asText(node.get("text")));
            if (name != null && !name.isBlank()) return name;
        }

        switch (kind) {
            case IMPACT_MODEL:
                return "Impact Diagram";
            case OBJECT_MODEL:
                return "Object Diagram";
            case STATE_MODEL:
                return "State Diagram";
            case PROCESS_MODEL:
                return "Process Diagram";
            case TASK_MODEL:
            default:
                return "Task Diagram";
        }
    }

    private static String nextToken(Map<String, Integer> prefixCounters, String prefix) {
        int next = prefixCounters.getOrDefault(prefix, 0) + 1;
        prefixCounters.put(prefix, next);
        return prefix + next;
    }

    private static int compareForExport(ReMoDeLEntity left, ReMoDeLEntity right) {
        int yDiff = Integer.compare(intProp(left, "y1"), intProp(right, "y1"));
        if (yDiff != 0) return yDiff;

        int xDiff = Integer.compare(intProp(left, "x1"), intProp(right, "x1"));
        if (xDiff != 0) return xDiff;

        String leftName = firstLine(asText(left.get("text")));
        String rightName = firstLine(asText(right.get("text")));
        int nameDiff = String.CASE_INSENSITIVE_ORDER.compare(leftName == null ? "" : leftName, rightName == null ? "" : rightName);
        if (nameDiff != 0) return nameDiff;

        return String.CASE_INSENSITIVE_ORDER.compare(left.getId() == null ? "" : left.getId(), right.getId() == null ? "" : right.getId());
    }

    private static int intProp(ReMoDeLEntity entity, String key) {
        if (entity == null) return 0;
        Object value = entity.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String toStringOrNull(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static String inferMetamodelName(List<ReMoDeLEntity> all) {
        for (ReMoDeLEntity e : all) {
            if (isConnectorEntity(e)) continue;
            if (!"BOUNDARY".equals(getShapeType(e))) continue;
            String text = asText(e.get("text"));
            if (text == null || text.isBlank()) continue;
            String name = sanitizeIdentifier(firstLine(text));
            if (!name.isBlank()) return name;
        }
        return "ExportedModel";
    }

    private static String inferConceptName(ReMoDeLEntity e) {
        String text = asText(e.get("text"));
        if (text == null || text.isBlank()) return sanitizeIdentifier(e.getId());
        return sanitizeIdentifier(firstLine(text));
    }

    private static List<String> inferConceptBodyLines(ReMoDeLEntity e) {
        List<String> lines = new ArrayList<>();
        String text = asText(e.get("text"));
        if (text == null || text.isBlank()) return lines;

        String[] parts = text.split("\\R");
        for (int i = 1; i < parts.length; i++) {
            String raw = parts[i].trim();
            if (raw.isEmpty()) continue;
            if (raw.startsWith("*")) raw = raw.substring(1).trim();

            if (raw.startsWith("attribute ") || raw.startsWith("reference ") || raw.startsWith("component ") || raw.startsWith("operation ")) {
                lines.add(raw);
                continue;
            }

            if (raw.contains(":")) {
                lines.add("attribute " + normalizeTypeDeclaration(raw));
            } else {
                lines.add("attribute " + sanitizeIdentifier(raw) + " : String");
            }
        }
        return lines;
    }

    private static String inferReferenceName(String label, String targetType) {
        String first = firstLine(label);
        if (first == null || first.isBlank()) return lowerFirst(targetType);
        String cleaned = first.trim();
        if (cleaned.startsWith("*")) cleaned = cleaned.substring(1).trim();

        // If label already includes multiplicity suffix, trim only for the field name.
        if (cleaned.endsWith("[]")) cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
        if (cleaned.contains(":")) cleaned = cleaned.substring(0, cleaned.indexOf(':')).trim();
        cleaned = sanitizeMemberIdentifier(cleaned);
        if (cleaned.isBlank()) return lowerFirst(targetType);
        return cleaned;
    }

    private static String inferTypeFromLabelOrTarget(String label, String targetType) {
        String first = firstLine(label);
        if (first == null) return targetType;
        String cleaned = first.trim();

        if (cleaned.contains(":")) {
            String t = cleaned.substring(cleaned.indexOf(':') + 1).trim();
            if (!t.isBlank()) return normalizeTypeDeclaration(t);
        }
        if (cleaned.endsWith("[]")) return targetType + "[]";
        return targetType;
    }

    private static boolean isKindOfQualifier(String label) {
        String second = secondLine(label);
        if (second == null) return false;
        String q = second.trim().toLowerCase();
        return q.equals("{kindof}") || q.equals("kindof") || q.equals("{kind_of}") || q.equals("kind_of");
    }

    private static String getShapeType(ReMoDeLEntity e) {
        Object shapeType = e.get("shapeType");
        if (shapeType == null) return "";
        return shapeType.toString().trim().toUpperCase();
    }

    private static String asText(Object v) {
        return v == null ? null : v.toString();
    }

    private static String firstLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\R", 2);
        return lines.length == 0 ? null : lines[0].trim();
    }

    private static String secondLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\R");
        if (lines.length < 2) return null;
        return lines[1].trim();
    }

    private static String sanitizeIdentifier(String input) {
        if (input == null || input.isBlank()) return "";
        String s = input.trim().replaceAll("[^A-Za-z0-9_]", " ").trim();
        if (s.isBlank()) return "";
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (out.length() == 0) {
                out.append(capitalize(p));
            } else {
                out.append(capitalize(p));
            }
        }
        String candidate = out.toString();
        if (candidate.isBlank()) candidate = "Unnamed";
        if (!Character.isLetter(candidate.charAt(0)) && candidate.charAt(0) != '_') {
            candidate = "_" + candidate;
        }
        return candidate;
    }

    private static String lowerFirst(String input) {
        if (input == null || input.isBlank()) return "value";
        return Character.toLowerCase(input.charAt(0)) + input.substring(1);
    }

    private static String capitalize(String input) {
        if (input == null || input.isBlank()) return "";
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private static String sanitizeMemberIdentifier(String input) {
        String typeLike = sanitizeIdentifier(input);
        return lowerFirst(typeLike);
    }

    private static String normalizeTypeDeclaration(String declaration) {
        if (declaration == null) return "String";
        String v = declaration.trim().replaceAll("\\s+", " ");
        v = v.replace(" :", ":").replace(":", " : ");
        return v.trim();
    }

    private static void addUnique(List<String> list, String value) {
        if (value == null || value.isBlank()) return;
        if (!list.contains(value)) list.add(value);
    }

    private static String entityToXML(ReMoDeLEntity entity, int indent) {
        String space = " ".repeat(indent);
        StringBuilder xml = new StringBuilder();
        xml.append(space).append("<entity id=\"").append(escape(entity.getId()))
           .append("\" type=\"").append(escape(entity.getType())).append("\">\n");

        for (Map.Entry<String, Object> prop : entity.getProperties().entrySet()) {
            String key = prop.getKey();
            Object val = prop.getValue();
            if (val != null && !(val instanceof java.awt.Shape)) {
                xml.append(space).append("  <property name=\"").append(escape(key))
                   .append("\" value=\"").append(escape(val.toString())).append("\"/>\n");
            }
        }
        xml.append(space).append("</entity>\n");
        return xml.toString();
    }

    private static String connectorToXML(ReMoDeLEntity connector, int indent) {
        String space = " ".repeat(indent);
        StringBuilder xml = new StringBuilder();
        xml.append(space).append("<connector id=\"").append(escape(connector.getId())).append("\">\n");
        xml.append(space).append("  <from>").append(escape(connector.get("fromId").toString())).append("</from>\n");
        xml.append(space).append("  <to>").append(escape(connector.get("toId").toString())).append("</to>\n");
        xml.append(space).append("  <type>").append(escape(connector.get("shapeType").toString())).append("</type>\n");
        xml.append(space).append("</connector>\n");
        return xml.toString();
    }

    private static String entityToJSON(ReMoDeLEntity entity, int indent) {
        String space = " ".repeat(indent);
        StringBuilder json = new StringBuilder();
        json.append(space).append("{\n");
        json.append(space).append("  \"id\": \"").append(entity.getId()).append("\",\n");
        json.append(space).append("  \"type\": \"").append(entity.getType()).append("\",\n");
        json.append(space).append("  \"properties\": {\n");

        List<Map.Entry<String, Object>> props = entity.getProperties().entrySet().stream()
            .filter(e -> !(e.getValue() instanceof java.awt.Shape))
            .collect(Collectors.toList());

        for (int i = 0; i < props.size(); i++) {
            Map.Entry<String, Object> prop = props.get(i);
            json.append(space).append("    \"").append(prop.getKey()).append("\": ");
            Object val = prop.getValue();
            if (val instanceof Number) {
                json.append(val);
            } else {
                json.append("\"").append(val).append("\"");
            }
            if (i < props.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append(space).append("  }\n");
        json.append(space).append("}");
        return json.toString();
    }

    private static String connectorToJSON(ReMoDeLEntity connector, int indent) {
        String space = " ".repeat(indent);
        StringBuilder json = new StringBuilder();
        json.append(space).append("{\n");
        json.append(space).append("  \"id\": \"").append(connector.getId()).append("\",\n");
        json.append(space).append("  \"from\": \"").append(connector.get("fromId")).append("\",\n");
        json.append(space).append("  \"to\": \"").append(connector.get("toId")).append("\",\n");
        json.append(space).append("  \"type\": \"").append(connector.get("shapeType")).append("\"\n");
        json.append(space).append("}");
        return json.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
               .replace("<", "&lt;")
               .replace(">", "&gt;")
               .replace("\"", "&quot;")
               .replace("'", "&apos;");
    }

    private static String escapeDsl(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
