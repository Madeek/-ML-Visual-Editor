package com.example.swingapp.persistence;

import com.example.swingapp.model.ReMoDeLEntity;
import com.example.swingapp.model.ReMoDeLModel;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Export to ReMoDeL model-instance DSL (.remodel text).
     */
    public static void exportToRemodelModel(ReMoDeLModel model, String filePath, ModelKind modelKind) throws IOException {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");

        ModelKind kind = modelKind == null ? ModelKind.TASK_MODEL : modelKind;

        StringBuilder out = new StringBuilder();
        out.append("model ").append(kind.dslName()).append(" {\n");
        out.append("    entities {\n");

        for (ReMoDeLEntity entity : model.getAll()) {
            if (isConnectorEntity(entity)) continue;
            out.append("        entity \"").append(escapeDsl(entity.getId())).append("\" type \"")
               .append(escapeDsl(entity.getType())).append("\" {")
               .append("\n");

            for (Map.Entry<String, Object> prop : entity.getProperties().entrySet()) {
                Object val = prop.getValue();
                if (val == null || val instanceof java.awt.Shape) continue;
                out.append("            ")
                   .append(sanitizeIdentifier(prop.getKey()))
                   .append(" = \"")
                   .append(escapeDsl(String.valueOf(val)))
                   .append("\"\n");
            }
            out.append("        }\n");
        }

        out.append("    }\n");
        out.append("    connectors {\n");

        for (ReMoDeLEntity entity : model.getAll()) {
            if (!isConnectorEntity(entity)) continue;
            String from = null;
            String to = null;

            Object fromObj = entity.get("fromId") != null ? entity.get("fromId") : entity.get("from");
            Object toObj = entity.get("toId") != null ? entity.get("toId") : entity.get("to");
            if (fromObj != null) from = String.valueOf(fromObj);
            if (toObj != null) to = String.valueOf(toObj);

            String shapeType = entity.get("shapeType") == null ? "connector" : String.valueOf(entity.get("shapeType"));
            String label = entity.get("text") == null ? "" : String.valueOf(entity.get("text"));

            out.append("        connector \"").append(escapeDsl(entity.getId())).append("\" type \"")
               .append(escapeDsl(shapeType)).append("\"");
            if (from != null) {
                out.append(" from \"").append(escapeDsl(from)).append("\"");
            }
            if (to != null) {
                out.append(" to \"").append(escapeDsl(to)).append("\"");
            }
            if (!label.isBlank()) {
                out.append(" label \"").append(escapeDsl(label)).append("\"");
            }
            out.append("\n");
        }

        out.append("    }\n");
        out.append("}\n");

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(out.toString());
        }
    }

    /**
     * Legacy entry point retained for existing callers.
     */
    public static void exportToRemodel(ReMoDeLModel model, String filePath) throws IOException {
        exportToRemodelModel(model, filePath, ModelKind.TASK_MODEL);
    }

    private static boolean isConnectorEntity(ReMoDeLEntity e) {
        return e != null && "connector".equalsIgnoreCase(e.getType());
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
