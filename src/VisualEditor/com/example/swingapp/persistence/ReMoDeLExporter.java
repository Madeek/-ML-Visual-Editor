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
 * Converts in-memory entities into XML, JSON, and ReMoDeL DSL exports.
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

    /**
     * Writes all nodes and connectors to an XML document.
     */
    public static void exportToXML(ReMoDeLModel model, String filePath) throws IOException {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<remodelDocument>\n");
        xml.append("  <entities>\n");


        for (ReMoDeLEntity entity : model.getAll()) {
            if (!isConnectorEntity(entity)) {
                xml.append(entityToXML(entity, 4));
            }
        }
        xml.append("  </entities>\n");
        xml.append("  <connectors>\n");


        for (ReMoDeLEntity entity : model.getAll()) {
            if (isConnectorEntity(entity)) {
                xml.append(connectorToXML(entity, 4));
            }
        }
        xml.append("  </connectors>\n");
        xml.append("</remodelDocument>\n");


        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(xml.toString());
        }
    }

    /**
     * Writes all nodes and connectors to a JSON document.
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
     * Writes a typed ReMoDeL DSL model for the requested model kind.
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
                out = buildProcessModelDsl(modelName, diagramName, nodes, connectors, filePath);
                break;
            case TASK_MODEL:
            default:
                out = buildTaskModelDsl(kind, modelName, diagramName, nodes, connectors, filePath);
                break;
        }

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(out);
        }
    }

    /**
     * Backward-compatible task-model export entry point.
     */
    public static void exportToRemodel(ReMoDeLModel model, String filePath) throws IOException {
        exportToRemodelModel(model, filePath, ModelKind.TASK_MODEL);
    }

    private static String buildTaskModelDsl(
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
            out.append(",");
            out.append(" ").append(collection.propertyName).append(" = ")
               .append(collection.elementType).append("[");
            if (!collection.entries.isEmpty()) {
                out.append("\n");
                for (int i = 0; i < collection.entries.size(); i++) {
                    out.append("      ").append(collection.entries.get(i));
                    if (i < collection.entries.size() - 1) out.append(",");
                    out.append("\n");
                }
                out.append("   ");
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
            out.append(",");
            out.append(" ").append(collection.propertyName).append(" = ")
               .append(collection.elementType).append("[");
            if (!collection.entries.isEmpty()) {
                out.append("\n");
                for (int i = 0; i < collection.entries.size(); i++) {
                    out.append("      ").append(collection.entries.get(i));
                    if (i < collection.entries.size() - 1) out.append(",");
                    out.append("\n");
                }
                out.append("   ");
            }
            out.append("]");
        }

        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static String buildProcessModelDsl(
            String modelName,
            String diagramName,
            List<ReMoDeLEntity> nodes,
            List<ReMoDeLEntity> connectors,
            String filePath) {

        Map<String, Integer> prefixCounters = new java.util.LinkedHashMap<>();
        java.util.List<ReMoDeLEntity> processNodes = nodes.stream()
            .filter(n -> "PROCESS".equals(getShapeType(n)))
            .collect(Collectors.toList());
        java.util.List<ReMoDeLEntity> actionNodes = nodes.stream()
            .filter(n -> "ACTION".equals(getShapeType(n)))
            .collect(Collectors.toList());
        java.util.List<ReMoDeLEntity> dataflowConnectors = connectors.stream()
            .filter(c -> "ARROW_FILLED".equals(getShapeType(c)))
            .collect(Collectors.toList());

        if (processNodes.isEmpty() && !actionNodes.isEmpty()) {
            processNodes = new ArrayList<>();
            processNodes.add(synthesizeProcessContainer(actionNodes, diagramName));
        }

        java.util.Map<String, ReMoDeLEntity> assignedProcessByActionId = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<ReMoDeLEntity>> actionsByProcessId = new java.util.LinkedHashMap<>();

        for (ReMoDeLEntity action : actionNodes) {
            ReMoDeLEntity owner = findContainingProcess(action, processNodes);
            if (owner == null) continue;
            assignedProcessByActionId.put(action.getId(), owner);
            actionsByProcessId.computeIfAbsent(owner.getId(), k -> new ArrayList<>()).add(action);
        }

        java.util.Map<String, String> processTokenById = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> actionTokenById = new java.util.LinkedHashMap<>();
        java.util.Map<String, ActionKind> actionKindById = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> actionNameById = new java.util.LinkedHashMap<>();

        for (ReMoDeLEntity process : processNodes) {
            String processToken = nextToken(prefixCounters, "p");
            processTokenById.put(process.getId(), processToken);

            java.util.List<ReMoDeLEntity> ownedActions = actionsByProcessId.get(process.getId());
            if (ownedActions == null) continue;
            ownedActions.sort(ReMoDeLExporter::compareForExport);

            for (ReMoDeLEntity action : ownedActions) {
                ActionLabelParts parts = parseActionLabel(asText(action.get("text")));
                String actionToken = nextToken(prefixCounters, actionPrefix(parts.kind));
                actionTokenById.put(action.getId(), actionToken);
                actionKindById.put(action.getId(), parts.kind);
                String rawName = firstLine(asText(action.get("text")));
                if (rawName == null || rawName.isBlank()) {
                    rawName = parts.kind.displayLabel() + " " + actionToken.substring(1);
                }
                actionNameById.put(action.getId(), rawName.trim());
            }
        }

        java.util.List<String> processEntries = new ArrayList<>();
        int unnamedProcessCount = 0;

        for (ReMoDeLEntity process : processNodes) {
            String processToken = processTokenById.get(process.getId());
            if (processToken == null) continue;

            String processName = firstLine(asText(process.get("text")));
            if (processName == null || processName.isBlank()) {
                unnamedProcessCount++;
                processName = "Process " + unnamedProcessCount;
            }

            java.util.List<ReMoDeLEntity> ownedActions = actionsByProcessId.getOrDefault(process.getId(), java.util.Collections.emptyList());
            java.util.List<ActionKind> actionKinds = new ArrayList<>();
            for (ReMoDeLEntity action : ownedActions) {
                ActionKind kind = actionKindById.get(action.getId());
                if (kind != null) actionKinds.add(kind);
            }
            String processKind = inferProcessKind(actionKinds);

            java.util.List<String> actionEntries = new ArrayList<>();
            for (ReMoDeLEntity action : ownedActions) {
                String actionToken = actionTokenById.get(action.getId());
                ActionKind kind = actionKindById.get(action.getId());
                if (actionToken == null || kind == null) continue;
                String actionName = actionNameById.getOrDefault(action.getId(), kind.displayLabel());

                java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
                fields.put("name", new ModValue(actionName, false));
                if (kind == ActionKind.INPUT && looksLikeIdentifierAction(actionName)) {
                    fields.put("id", new ModValue("true", true));
                } else if (kind == ActionKind.STORE && "update".equals(processKind)) {
                    fields.put("again", new ModValue("true", true));
                } else if (kind == ActionKind.DELETE && looksLikeArchiveAction(processName, actionName)) {
                    fields.put("archive", new ModValue("true", true));
                }
                actionEntries.add(buildEntry(actionToken, kind.displayLabel, fields));
            }

            java.util.List<String> dataflowEntries = new ArrayList<>();
            for (ReMoDeLEntity connector : dataflowConnectors) {
                String sourceId = toStringOrNull(connector.get("fromId"));
                if (sourceId == null) sourceId = toStringOrNull(connector.get("from"));
                String targetId = toStringOrNull(connector.get("toId"));
                if (targetId == null) targetId = toStringOrNull(connector.get("to"));
                if (sourceId == null || targetId == null) continue;

                ReMoDeLEntity sourceProcess = assignedProcessByActionId.get(sourceId);
                ReMoDeLEntity targetProcess = assignedProcessByActionId.get(targetId);
                if (sourceProcess == null || targetProcess == null) continue;
                if (!process.getId().equals(sourceProcess.getId()) || !process.getId().equals(targetProcess.getId())) continue;

                String sourceToken = actionTokenById.get(sourceId);
                String targetToken = actionTokenById.get(targetId);
                ActionKind sourceKind = actionKindById.get(sourceId);
                ActionKind targetKind = actionKindById.get(targetId);
                if (sourceToken == null || targetToken == null || sourceKind == null || targetKind == null) continue;

                String label = asText(connector.get("text"));
                ProcessDatumParts datumParts = parseProcessDatumParts(label);
                ProcessDatumKind datumKind = inferProcessDatumKind(datumParts, sourceKind, targetKind);
                String datumName = inferProcessDatumName(datumParts, label, sourceKind, targetKind, actionNameById.get(sourceId), actionNameById.get(targetId));
                String datumToken = nextToken(prefixCounters, datumPrefix(datumKind));

                java.util.LinkedHashMap<String, ModValue> datumFields = new java.util.LinkedHashMap<>();
                datumFields.put("name", new ModValue(datumName, false));
                if (datumParts.subtypes != null && !datumParts.subtypes.isBlank()) {
                    datumFields.put("kinds", new ModValue(datumParts.subtypes, false));
                }

                StringBuilder dataflow = new StringBuilder();
                dataflow.append(nextToken(prefixCounters, "d")).append(" : Dataflow(source = ")
                        .append(sourceToken).append(", target = ").append(targetToken).append(", datum = \n")
                        .append("            ").append(buildEntry(datumToken, datumElementType(datumKind), datumFields)).append("\n")
                        .append("      )");
                dataflowEntries.add(dataflow.toString());
            }

            StringBuilder processEntry = new StringBuilder();
            processEntry.append(processToken).append(" : Process(name = \"").append(escapeDsl(processName)).append("\"");
            processEntry.append(", kind = \"").append(escapeDsl(processKind)).append("\"");

            if (!actionEntries.isEmpty()) {
                processEntry.append(", actions = Action[\n");
                for (int i = 0; i < actionEntries.size(); i++) {
                    processEntry.append("         ").append(actionEntries.get(i));
                    if (i < actionEntries.size() - 1) processEntry.append(",");
                    processEntry.append("\n");
                }
                processEntry.append("      ]");
            }
            if (!dataflowEntries.isEmpty()) {
                processEntry.append(", dataflows = Dataflow[\n");
                for (int i = 0; i < dataflowEntries.size(); i++) {
                    processEntry.append("         ").append(dataflowEntries.get(i));
                    if (i < dataflowEntries.size() - 1) processEntry.append(",");
                    processEntry.append("\n");
                }
                processEntry.append("      ]");
            }
            processEntry.append(")");
            processEntries.add(processEntry.toString());
        }

        StringBuilder out = new StringBuilder();
        appendCreatedByComment(out, filePath);
        out.append("model ").append(modelName).append(" : ProcessModel {\n");
        out.append("   d1 : Diagram(name = \"").append(escapeDsl(diagramName)).append("\"");
        if (!processEntries.isEmpty()) {
            out.append(", processes = Process[\n");
            for (int i = 0; i < processEntries.size(); i++) {
                out.append("      ").append(processEntries.get(i));
                if (i < processEntries.size() - 1) out.append(",");
                out.append("\n");
            }
            out.append("   ]");
        }
        out.append(")\n");
        out.append("}\n");
        return out.toString();
    }

    private static ReMoDeLEntity synthesizeProcessContainer(List<ReMoDeLEntity> actionNodes, String diagramName) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (ReMoDeLEntity action : actionNodes) {
            java.awt.geom.Rectangle2D bounds = entityBounds(action);
            if (bounds == null) continue;
            minX = Math.min(minX, bounds.getMinX());
            minY = Math.min(minY, bounds.getMinY());
            maxX = Math.max(maxX, bounds.getMaxX());
            maxY = Math.max(maxY, bounds.getMaxY());
        }

        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            minX = 0.0;
            minY = 0.0;
            maxX = 400.0;
            maxY = 250.0;
        }

        double padX = Math.max(80.0, (maxX - minX) * 0.20);
        double padY = Math.max(60.0, (maxY - minY) * 0.20);

        ReMoDeLEntity process = new ReMoDeLEntity();
        process.setType("shape");
        process.put("shapeType", "PROCESS");
        process.put("text", deriveProcessNameFromActions(actionNodes, diagramName));
        process.put("x1", (int) Math.round(minX - padX));
        process.put("y1", (int) Math.round(minY - padY));
        process.put("x2", (int) Math.round(maxX + padX));
        process.put("y2", (int) Math.round(maxY + padY));
        return process;
    }

    private static String deriveProcessNameFromActions(List<ReMoDeLEntity> actionNodes, String diagramName) {
        if (actionNodes == null || actionNodes.isEmpty()) {
            return diagramName == null || diagramName.isBlank() ? "Process" : diagramName.trim();
        }

        String firstAction = firstLine(asText(actionNodes.get(0).get("text")));
        if (firstAction == null || firstAction.isBlank()) {
            return diagramName == null || diagramName.isBlank() ? "Process" : diagramName.trim();
        }

        String cleaned = firstAction.trim();
        String[] prefixes = { "Input ", "Output ", "Fetch ", "Create ", "Update ", "Store ", "Delete ", "Process " };
        for (String prefix : prefixes) {
            if (cleaned.regionMatches(true, 0, prefix, 0, prefix.length())) {
                cleaned = cleaned.substring(prefix.length()).trim();
                break;
            }
        }

        if (cleaned.isBlank()) {
            return diagramName == null || diagramName.isBlank() ? "Process" : diagramName.trim();
        }
        return cleaned;
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
                return "OBJECT_TYPE".equals(shape) || "OBJECT".equals(shape);
            })
            .collect(Collectors.toList());

        List<String> objectEntries = new ArrayList<>();


        Map<String, String> objectNameById = new java.util.LinkedHashMap<>();
        Map<String, List<String>> localPropsById = new java.util.LinkedHashMap<>();
        for (ReMoDeLEntity node : objectNodes) {
            String token = nextToken(counters, "o");
            objectTokenById.put(node.getId(), token);
            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) name = "ObjectType " + token.substring(1);
            objectNameById.put(node.getId(), name);

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
            }
            localPropsById.put(node.getId(), localProperties);
        }

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
                List<String> props = localPropsById.get(sourceId);
                if (props == null) {
                    props = new ArrayList<>();
                    localPropsById.put(sourceId, props);
                }
                StringBuilder refProp = new StringBuilder();
                refProp.append(token).append(" : Reference(name = \"").append(escapeDsl(name)).append("\"");
                String label = asText(connector.get("text"));
                String first = firstLine(label);
                if (first != null && first.trim().startsWith("*")) refProp.append(", id = true");
                String refType = inferTypeFromLabelOrTarget(label, targetToken);
                refProp.append(", type = ").append(refType);
                if (isKindOfQualifier(label)) refProp.append(", kindOf = true");
                refProp.append(")");
                props.add(refProp.toString());
            } else if ("ARROW_EMPTY".equals(shape)) {
                String token = nextToken(counters, "g");
                generalisationEntries.add(token + " : Generalisation(source = " + sourceToken + ", target = " + targetToken + ")");
            }
        }


        for (ReMoDeLEntity node : objectNodes) {
            String id = node.getId();
            String token = objectTokenById.get(id);
            String name = objectNameById.get(id);
            List<String> props = localPropsById.getOrDefault(id, java.util.Collections.emptyList());

            StringBuilder object = new StringBuilder();
            object.append(token).append(" : ObjectType(name = \"").append(escapeDsl(name)).append("\"");
            if (!props.isEmpty()) {
                object.append(", properties = Property[\n");
                for (int i = 0; i < props.size(); i++) {
                    object.append("         ").append(props.get(i));
                    if (i < props.size() - 1) object.append(",");
                    object.append("\n");
                }
                object.append("      ]");
            }
            object.append(")");
            objectEntries.add(object.toString());
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

            TransitionLabelParts labelParts = parseTransitionLabel(asText(connector.get("text")));
            String transitionName = (labelParts.event == null || labelParts.event.isBlank())
                ? defaultTransitionName(shape)
                : labelParts.event.trim();
            String guardText = labelParts.guard == null || labelParts.guard.isBlank() ? null : labelParts.guard.trim();
            String actionProc = labelParts.action == null || labelParts.action.isBlank() ? null : labelParts.action.trim();
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

    private static String defaultTransitionName(String shapeType) {
        if ("INITIAL_TRANSITION".equals(shapeType)) return "entry";
        if ("FINAL_TRANSITION".equals(shapeType)) return "exit";
        return "transition";
    }

    private static final class TransitionLabelParts {
        final String event;
        final String guard;
        final String action;

        TransitionLabelParts(String event, String guard, String action) {
            this.event = event;
            this.guard = guard;
            this.action = action;
        }
    }

    private static TransitionLabelParts parseTransitionLabel(String text) {
        if (text == null) return new TransitionLabelParts("", "", "");

        String normalized = text.trim().replaceAll("\\R+", " ").replaceAll("\\s+", " ");
        if (normalized.isBlank()) return new TransitionLabelParts("", "", "");

        String event = normalized;
        String guard = "";
        String action = "";

        int actionSep = normalized.lastIndexOf(" / ");
        if (actionSep >= 0) {
            action = normalized.substring(actionSep + 3).trim();
            normalized = normalized.substring(0, actionSep).trim();
        }

        int guardOpen = normalized.indexOf('[');
        int guardClose = normalized.lastIndexOf(']');
        if (guardOpen >= 0 && guardClose > guardOpen) {
            guard = normalized.substring(guardOpen + 1, guardClose).trim();
            event = normalized.substring(0, guardOpen).trim();
        } else {
            event = normalized.trim();
        }

        return new TransitionLabelParts(event, guard, action);
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
                collections.add(buildNodeCollection("tasks", "Task", nodes, nodeTokenById, prefixCounters, "t", "Task", "TASK"));
                collections.add(buildNodeCollection("objects", "Object", nodes, nodeTokenById, prefixCounters, "o", "Object", "OBJECT", "OBJECT_TYPE"));
                collections.add(buildConnectorCollection("impacts", "Impact", connectors, nodeTokenById, prefixCounters, "i", "IMPACT", false, ReMoDeLExporter::extractImpactKind));
                collections.add(buildConnectorCollection("generalisations", "Generalisation", connectors, nodeTokenById, prefixCounters, "g", "ARROW_EMPTY", false, null));
                collections.add(buildConnectorCollection("compositions", "Composition", connectors, nodeTokenById, prefixCounters, "c", "ARROW_DIAMOND", false, null));
                break;
            case OBJECT_MODEL:
                collections.add(buildNodeCollection("objectTypes", "ObjectType", nodes, nodeTokenById, prefixCounters, "o", "ObjectType", "OBJECT_TYPE", "OBJECT"));
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
                collections.add(buildProcessCollection(nodes, nodeTokenById, prefixCounters));
                collections.add(buildActionCollection(nodes, nodeTokenById, prefixCounters));
                collections.add(buildConnectorCollection("dataflows", "Dataflow", connectors, nodeTokenById, prefixCounters, "f", "ARROW_FILLED", false, null));
                break;
            case TASK_MODEL:
            default:
                collections.add(buildNodeCollection("actors", "Actor", nodes, nodeTokenById, prefixCounters, "a", "Actor", "ACTOR"));
                collections.add(buildSystemActorCollection(nodes, nodeTokenById, prefixCounters));
                collections.add(buildNodeCollection("tasks", "Task", nodes, nodeTokenById, prefixCounters, "t", "Task", "TASK", "BOUNDARY"));
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

    private static ModCollection buildSystemActorCollection(
            List<ReMoDeLEntity> nodes,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters) {

        ModCollection collection = new ModCollection("actors", "Actor");
        int unnamedCount = 0;
        for (ReMoDeLEntity node : nodes) {
            String shapeType = getShapeType(node);
            if (!"SYSTEM".equals(shapeType)) continue;

            String token = nextToken(prefixCounters, "a");
            nodeTokenById.put(node.getId(), token);

            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) {
                unnamedCount++;
                name = "System " + unnamedCount;
            }

            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("name", new ModValue(name, false));
            fields.put("system", new ModValue("true", true));
            collection.entries.add(buildEntry(token, "Actor", fields));
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

    private static ModCollection buildActionCollection(
            List<ReMoDeLEntity> nodes,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters) {

        ModCollection collection = new ModCollection("actions", "Action");
        int unnamedCount = 0;
        for (ReMoDeLEntity node : nodes) {
            if (!"ACTION".equals(getShapeType(node))) continue;

            String token = nextToken(prefixCounters, "a");
            nodeTokenById.put(node.getId(), token);

            ActionLabelParts parts = parseActionLabel(asText(node.get("text")));
            String name = parts.name;
            if (name == null || name.isBlank()) {
                unnamedCount++;
                name = parts.kind.displayLabel() + " " + unnamedCount;
            }

            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("name", new ModValue(name, false));
            fields.put("kind", new ModValue(parts.kind.dslKind(), false));
            collection.entries.add(buildEntry(token, "Action", fields));
        }
        return collection;
    }

    private static ModCollection buildProcessCollection(
            List<ReMoDeLEntity> nodes,
            Map<String, String> nodeTokenById,
            Map<String, Integer> prefixCounters) {

        ModCollection collection = new ModCollection("processes", "Process");
        int unnamedCount = 0;
        for (ReMoDeLEntity node : nodes) {
            if (!"PROCESS".equals(getShapeType(node))) continue;

            String token = nextToken(prefixCounters, "p");
            nodeTokenById.put(node.getId(), token);

            String name = firstLine(asText(node.get("text")));
            if (name == null || name.isBlank()) {
                unnamedCount++;
                name = "Process " + unnamedCount;
            }

            java.util.LinkedHashMap<String, ModValue> fields = new java.util.LinkedHashMap<>();
            fields.put("name", new ModValue(name, false));
            collection.entries.add(buildEntry(token, "Process", fields));
        }
        return collection;
    }

    private enum ProcessDatumKind {
        OBJECT,
        CONTENT,
        IDENTITY
    }

    private static class ProcessDatumParts {
        final String name;
        final String subtypes;
        final String annotation;

        ProcessDatumParts(String name, String subtypes, String annotation) {
            this.name = name;
            this.subtypes = subtypes;
            this.annotation = annotation;
        }
    }

    private static String actionPrefix(ActionKind kind) {
        if (kind == null) return "a";
        switch (kind) {
            case INPUT: return "i";
            case OUTPUT: return "o";
            case FETCH: return "f";
            case STORE: return "s";
            case CREATE: return "c";
            case UPDATE: return "u";
            case DELETE: return "d";
            default: return "a";
        }
    }

    private static String datumPrefix(ProcessDatumKind kind) {
        if (kind == null) return "o";
        switch (kind) {
            case IDENTITY: return "i";
            case CONTENT: return "c";
            case OBJECT:
            default: return "o";
        }
    }

    private static String datumElementType(ProcessDatumKind kind) {
        if (kind == null) return "Object";
        switch (kind) {
            case IDENTITY: return "Identity";
            case CONTENT: return "Content";
            case OBJECT:
            default: return "Object";
        }
    }

    private static String inferProcessKind(java.util.List<ActionKind> actionKinds) {
        boolean hasCreate = false;
        boolean hasUpdate = false;
        boolean hasDelete = false;
        boolean hasFetch = false;
        boolean hasOutput = false;

        for (ActionKind kind : actionKinds) {
            if (kind == null) continue;
            switch (kind) {
                case CREATE:
                    hasCreate = true;
                    break;
                case UPDATE:
                    hasUpdate = true;
                    break;
                case DELETE:
                    hasDelete = true;
                    break;
                case FETCH:
                    hasFetch = true;
                    break;
                case OUTPUT:
                    hasOutput = true;
                    break;
                default:
                    break;
            }
        }

        if (hasDelete) return "delete";
        if (hasUpdate) return "update";
        if (hasCreate) return "create";
        if (hasFetch || hasOutput) return "read";
        return "create";
    }

    private static boolean looksLikeIdentifierAction(String actionName) {
        if (actionName == null) return false;
        return actionName.toLowerCase().contains("id");
    }

    private static boolean looksLikeArchiveAction(String processName, String actionName) {
        String process = processName == null ? "" : processName.toLowerCase();
        String action = actionName == null ? "" : actionName.toLowerCase();
        return process.contains("discharge") || action.contains("archive");
    }

    private static ProcessDatumParts parseProcessDatumParts(String label) {
        String raw = label != null ? label.trim() : "";
        String annotation = "";
        if (raw.endsWith("{val}")) {
            annotation = "{val}";
            raw = raw.substring(0, raw.length() - 5).trim();
        } else if (raw.endsWith("{id}")) {
            annotation = "{id}";
            raw = raw.substring(0, raw.length() - 4).trim();
        }

        String subtypes = "";
        int open = raw.indexOf('(');
        int close = raw.lastIndexOf(')');
        if (open > 0 && close > open) {
            subtypes = raw.substring(open + 1, close).trim();
            raw = raw.substring(0, open).trim();
        }

        if (raw.isBlank()) raw = "Object";
        return new ProcessDatumParts(raw, subtypes, annotation);
    }

    private static ProcessDatumKind inferProcessDatumKind(ProcessDatumParts parts, ActionKind sourceKind, ActionKind targetKind) {
        if (parts != null && parts.annotation != null) {
            String annotation = parts.annotation.trim().toLowerCase();
            if (annotation.equals("{id}")) return ProcessDatumKind.IDENTITY;
            if (annotation.equals("{val}")) return ProcessDatumKind.CONTENT;
        }

        String explicit = parts != null && parts.name != null ? parts.name.trim().toLowerCase() : "";
        if (explicit.equals("identity")) return ProcessDatumKind.IDENTITY;
        if (explicit.equals("content")) return ProcessDatumKind.CONTENT;
        if (explicit.equals("object")) return ProcessDatumKind.OBJECT;

        if (sourceKind == ActionKind.INPUT) {
            if (targetKind == ActionKind.FETCH || targetKind == ActionKind.OUTPUT || targetKind == ActionKind.DELETE) {
                return ProcessDatumKind.IDENTITY;
            }
            if (targetKind == ActionKind.CREATE || targetKind == ActionKind.UPDATE) {
                return ProcessDatumKind.CONTENT;
            }
        }

        if (parts != null && parts.subtypes != null && !parts.subtypes.isBlank()) {
            return ProcessDatumKind.OBJECT;
        }

        return ProcessDatumKind.OBJECT;
    }

    private static String inferProcessDatumName(
            ProcessDatumParts parts,
            String rawLabel,
            ActionKind sourceKind,
            ActionKind targetKind,
            String sourceActionName,
            String targetActionName) {

        String explicit = parts != null && parts.name != null ? parts.name.trim() : "";
        if (!explicit.isBlank() && !isGenericDatumName(explicit)) {
            return explicit;
        }

        String sourceGuess = extractDomainNameFromAction(sourceActionName, sourceKind);
        String targetGuess = extractDomainNameFromAction(targetActionName, targetKind);

        if (!sourceGuess.isBlank()) return sourceGuess;
        if (!targetGuess.isBlank()) return targetGuess;

        if (explicit.isBlank()) {
            ProcessDatumParts fallback = parseProcessDatumParts(rawLabel);
            if (fallback.name != null && !fallback.name.isBlank()) return fallback.name;
        }
        return explicit.isBlank() ? "Object" : explicit;
    }

    private static boolean isGenericDatumName(String name) {
        if (name == null) return true;
        String lower = name.trim().toLowerCase();
        return lower.isEmpty() || lower.equals("object") || lower.equals("content") || lower.equals("identity");
    }

    private static String extractDomainNameFromAction(String actionName, ActionKind kind) {
        if (actionName == null || actionName.isBlank()) return "";
        String cleaned = actionName.trim();
        String[] prefixes = { "Input", "Output", "Fetch", "Create", "Update", "Store", "Delete" };
        for (String prefix : prefixes) {
            if (cleaned.regionMatches(true, 0, prefix, 0, prefix.length())) {
                cleaned = cleaned.substring(prefix.length()).trim();
                break;
            }
        }
        cleaned = cleaned.replaceAll("(?i)\\b(values?|id)\\b", "");
        cleaned = cleaned.replaceAll("[\\p{Punct}]+$", "").trim();
        if (cleaned.isBlank()) return "";
        return cleaned;
    }

    private static ReMoDeLEntity findContainingProcess(ReMoDeLEntity action, java.util.List<ReMoDeLEntity> processes) {
        if (action == null || processes == null || processes.isEmpty()) return null;

        java.awt.geom.Rectangle2D actionBounds = entityBounds(action);
        if (actionBounds == null) return null;

        ReMoDeLEntity best = null;
        double bestArea = Double.POSITIVE_INFINITY;
        for (ReMoDeLEntity process : processes) {
            java.awt.geom.Rectangle2D processBounds = entityBounds(process);
            if (processBounds == null) continue;
            


            double margin = 8.0;
            double processLeft = processBounds.getMinX() + margin;
            double processTop = processBounds.getMinY() + margin;
            double processRight = processBounds.getMaxX() - margin;
            double processBottom = processBounds.getMaxY() - margin;
            
            double actionLeft = actionBounds.getMinX();
            double actionTop = actionBounds.getMinY();
            double actionRight = actionBounds.getMaxX();
            double actionBottom = actionBounds.getMaxY();
            

            if (actionLeft < processLeft || actionRight > processRight || 
                actionTop < processTop || actionBottom > processBottom) {
                continue;
            }

            double area = Math.max(0.0, processBounds.getWidth()) * Math.max(0.0, processBounds.getHeight());
            if (area < bestArea) {
                bestArea = area;
                best = process;
            }
        }
        return best;
    }

    private static java.awt.geom.Rectangle2D entityBounds(ReMoDeLEntity entity) {
        if (entity == null) return null;
        double x1 = intProp(entity, "x1");
        double y1 = intProp(entity, "y1");
        double x2 = intProp(entity, "x2");
        double y2 = intProp(entity, "y2");
        double left = Math.min(x1, x2);
        double top = Math.min(y1, y2);
        double width = Math.abs(x2 - x1);
        double height = Math.abs(y2 - y1);
        return new java.awt.geom.Rectangle2D.Double(left, top, width, height);
    }

    private static ActionLabelParts parseActionLabel(String label) {
        String raw = label != null ? label.trim() : "";
        if (raw.isBlank()) {
            return new ActionLabelParts(ActionKind.INPUT, "");
        }

        for (ActionKind kind : ActionKind.values()) {
            String kindLabel = kind.displayLabel;
            if (raw.equalsIgnoreCase(kindLabel)) {
                return new ActionLabelParts(kind, "");
            }
            if (raw.regionMatches(true, 0, kindLabel + ":", 0, kindLabel.length() + 1)) {
                return new ActionLabelParts(kind, raw.substring(kindLabel.length() + 1).trim());
            }
        }

        int colon = raw.indexOf(':');
        if (colon > 0) {
            String left = raw.substring(0, colon).trim();
            String right = raw.substring(colon + 1).trim();
            for (ActionKind kind : ActionKind.values()) {
                if (left.equalsIgnoreCase(kind.displayLabel) || left.equalsIgnoreCase(kind.dslKind)) {
                    return new ActionLabelParts(kind, right);
                }
            }
        }

        String lower = raw.toLowerCase();
        ActionKind inferred = ActionKind.INPUT;
        if (lower.contains("output")) inferred = ActionKind.OUTPUT;
        else if (lower.contains("fetch")) inferred = ActionKind.FETCH;
        else if (lower.contains("store")) inferred = ActionKind.STORE;
        else if (lower.contains("create")) inferred = ActionKind.CREATE;
        else if (lower.contains("update")) inferred = ActionKind.UPDATE;
        else if (lower.contains("delete") || lower.contains("archive") || lower.contains("remove")) inferred = ActionKind.DELETE;

        return new ActionLabelParts(inferred, raw);
    }

    private static class ActionLabelParts {
        final ActionKind kind;
        final String name;

        ActionLabelParts(ActionKind kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    private enum ActionKind {
        INPUT("InputAction", "input"),
        OUTPUT("OutputAction", "output"),
        FETCH("FetchAction", "fetch"),
        STORE("StoreAction", "store"),
        CREATE("CreateAction", "create"),
        UPDATE("UpdateAction", "update"),
        DELETE("DeleteAction", "delete");

        final String displayLabel;
        final String dslKind;

        ActionKind(String displayLabel, String dslKind) {
            this.displayLabel = displayLabel;
            this.dslKind = dslKind;
        }

        String displayLabel() {
            return displayLabel;
        }

        String dslKind() {
            return dslKind;
        }
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


        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) return name;

        String sanitized = sanitizeIdentifier(name);
        return sanitized.isBlank() ? null : sanitized;
    }

    private static String inferDiagramName(ModelKind kind, List<ReMoDeLEntity> nodes) {
        String preferredShape = "BOUNDARY";

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

    private static String inferReferenceName(String label, String targetToken) {
        String first = firstLine(label);
        if (first == null || first.isBlank()) {
            if (targetToken != null && targetToken.length() > 1) return lowerFirst(targetToken.substring(1));
            return "ref";
        }
        String cleaned = first.trim();
        if (cleaned.startsWith("*")) cleaned = cleaned.substring(1).trim();
        if (cleaned.contains(":")) cleaned = cleaned.substring(0, cleaned.indexOf(':')).trim();
        if (cleaned.isEmpty()) {
            if (targetToken != null && targetToken.length() > 1) return lowerFirst(targetToken.substring(1));
            return "ref";
        }
        return sanitizeMemberIdentifier(cleaned);
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
        if (connector.get("manualPosition") != null) {
            xml.append(space).append("  <manualPosition>").append(escape(connector.get("manualPosition").toString())).append("</manualPosition>\n");
        }
        if (connector.get("x1") != null) xml.append(space).append("  <x1>").append(escape(connector.get("x1").toString())).append("</x1>\n");
        if (connector.get("y1") != null) xml.append(space).append("  <y1>").append(escape(connector.get("y1").toString())).append("</y1>\n");
        if (connector.get("x2") != null) xml.append(space).append("  <x2>").append(escape(connector.get("x2").toString())).append("</x2>\n");
        if (connector.get("y2") != null) xml.append(space).append("  <y2>").append(escape(connector.get("y2").toString())).append("</y2>\n");
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
        json.append(space).append("  \"type\": \"").append(connector.get("shapeType")).append("\"");
        if (connector.get("manualPosition") != null) {
            json.append(",\n").append(space).append("  \"manualPosition\": ").append(connector.get("manualPosition"));
        }
        if (connector.get("x1") != null) {
            json.append(",\n").append(space).append("  \"x1\": ").append(connector.get("x1"));
        }
        if (connector.get("y1") != null) {
            json.append(",\n").append(space).append("  \"y1\": ").append(connector.get("y1"));
        }
        if (connector.get("x2") != null) {
            json.append(",\n").append(space).append("  \"x2\": ").append(connector.get("x2"));
        }
        if (connector.get("y2") != null) {
            json.append(",\n").append(space).append("  \"y2\": ").append(connector.get("y2"));
        }
        json.append("\n");
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
