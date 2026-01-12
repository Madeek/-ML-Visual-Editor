package com.example.swingapp.model;

import java.io.*;
import java.util.List;
import java.util.Map;

/**
 * Exports ReMoDeL model to XML format suitable for ReMoDeL Model-Driven Engineering toolkit.
 */
public class ReMoDeLExporter {

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
            .toList();
        
        for (int i = 0; i < nodeEntities.size(); i++) {
            json.append(entityToJSON(nodeEntities.get(i), 4));
            if (i < nodeEntities.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"connectors\": [\n");

        List<ReMoDeLEntity> connectorEntities = allEntities.stream()
            .filter(ReMoDeLExporter::isConnectorEntity)
            .toList();
        
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

    private static boolean isConnectorEntity(ReMoDeLEntity e) {
        return e != null && "connector".equalsIgnoreCase(e.getType());
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
            .toList();

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
}
