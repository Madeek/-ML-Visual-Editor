// This class implements a drawing canvas with basic shape tools, selection, and editing.
package com.example.swingapp.view;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Paint;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.InputMap;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

import com.example.swingapp.model.ModelEvent;
import com.example.swingapp.model.ModelListener;
import com.example.swingapp.model.ReMoDeLEntity;
import com.example.swingapp.model.ReMoDeLModel;

public class DrawingCanvas extends JComponent {
    private BufferedImage buf;
    private Color drawColor = Color.BLACK;
    private float strokeWidth = 3f;
    private String referenceDefaultName = "member";
    private String referenceQualifier = "";
    private int lastX = -1, lastY = -1;
    private Consumer<String> statusConsumer = s -> {};
    private final UndoManager undoManager = new UndoManager();
    // currently active move/resize undoable edit (grouped per drag)
    private MoveEdit currentMoveEdit = null;

    // helper to create a defensive copy of a ShapeRecord (reconstruct shapes from numeric coords)
    private ShapeRecord copyShapeRecord(ShapeRecord r) {
        if (r == null) return null;
        if (r.tool == Tool.TEXT) {
            double x = r.x1, y = r.y1, w = r.x2 - r.x1, h = r.y2 - r.y1;
            Shape rect = new Rectangle2D.Double(x, y, w, h);
            return new ShapeRecord(Tool.TEXT, rect, r.color, r.stroke, r.x1, r.y1, r.x2, r.y2, r.text, r.font,
                    r.entityId, r.localId, r.anchorFromId, r.anchorToId);
        } else {
            // use existing factory to rebuild shape from coords
            ShapeRecord nr = createRecordFromTool(r.tool, r.color, r.stroke, (int) Math.round(r.x1), (int) Math.round(r.y1), (int) Math.round(r.x2), (int) Math.round(r.y2));
            if (nr != null) {
                return new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2,
                        r.text, r.font, r.entityId, r.localId, r.anchorFromId, r.anchorToId);
            }
            return r;
        }
    }

    // --- Model wiring -----------------------------------------------------------------
    private ModelListener modelListener = null;

    public void setModel(ReMoDeLModel m) {
        if (this.model != null && modelListener != null) {
            this.model.removeListener(modelListener);
        }
        this.model = m;
        idToIndex.clear();
        shapes.clear();
        if (m == null) {
            redrawBuffer();
            repaint();
            return;
        }
        // build initial shapes from model
        rebuildShapesFromModel();

        // register listener to keep canvas in sync
        modelListener = new ModelListener() {
            @Override
            public void modelChanged(ModelEvent e) {
                // Ensure UI updates happen on EDT
                SwingUtilities.invokeLater(() -> {
                    rebuildShapesFromModel();
                });
            }
        };
        m.addListener(modelListener);
    }

    private void rebuildShapesFromModel() {
        if (model == null) return;
        shapes.clear();
        idToIndex.clear();

        java.util.List<ReMoDeLEntity> all = model.getAll();
        Map<String, ReMoDeLEntity> entityIndex = new HashMap<>();

        // first pass: shapes (non-connectors) so anchors exist
        for (ReMoDeLEntity e : all) {
            if (isConnectorEntity(e)) continue;
            ShapeRecord r = shapeFromEntity(e);
            if (r != null) {
                idToIndex.put(e.getId(), shapes.size());
                shapes.add(r);
            }
            entityIndex.put(e.getId(), e);
        }

        // second pass: connectors so we can resolve endpoints using stored shapes
        for (ReMoDeLEntity e : all) {
            if (!isConnectorEntity(e)) continue;
            ShapeRecord r = shapeFromConnector(e, entityIndex);
            if (r != null) {
                shapes.add(r);
            }
        }

        // Re-sort: larger shapes behind smaller ones, connectors always on top.
        // Then rebuild idToIndex with the new positions.
        sortShapesByArea();
        idToIndex.clear();
        for (int i = 0; i < shapes.size(); i++) {
            ShapeRecord r = shapes.get(i);
            if (r.entityId != null) idToIndex.put(r.entityId, i);
        }

        redrawBuffer();
        repaint();
    }

    private boolean isConnectorEntity(ReMoDeLEntity e) {
        return e != null && "connector".equalsIgnoreCase(e.getType());
    }

    private boolean isConnectorTool(Tool t) {
        return t == Tool.LINE || t == Tool.AUTHORISATION || t == Tool.ARROW_FILLED || t == Tool.ARROW_EMPTY
            || t == Tool.INITIAL_TRANSITION || t == Tool.FINAL_TRANSITION
            || t == Tool.ARROW_DIAMOND || t == Tool.ARROW_OPEN || t == Tool.IMPACT
            || t == Tool.REFERENCE || t == Tool.ENACTS;
    }

    private boolean isTransitionTool(Tool t) {
        return t == Tool.ARROW_OPEN || t == Tool.INITIAL_TRANSITION || t == Tool.FINAL_TRANSITION;
    }

    private boolean isSelfLoopTransition(Tool tool, ShapeRecord fromShape, ShapeRecord toShape) {
        if (!isTransitionTool(tool) || fromShape == null || toShape == null) return false;
        if (fromShape.localId == null || toShape.localId == null) return false;
        return fromShape.localId.equals(toShape.localId);
    }

    private boolean isSelfLoopTransition(ShapeRecord r) {
        if (r == null || !isTransitionTool(r.tool)) return false;
        if (r.anchorFromId == null || r.anchorToId == null) return false;
        return r.anchorFromId.equals(r.anchorToId);
    }

    private String getConnectorLabel(Tool tool, String existingText) {
        if (!isConnectorTool(tool)) return existingText;
        if (existingText != null && !existingText.isBlank()) return existingText;
        return getDefaultLabelForTool(tool);
    }

    private int findAnchorTarget(int x, int y) {
        return findAnchorTarget(x, y, HANDLE_HIT_MARGIN);
    }

    private int findAnchorTarget(int x, int y, int extraMargin) {
        return findAnchorTarget(x, y, extraMargin, true);
    }

    private int findConnectorAnchorTarget(int x, int y, int extraMargin) {
        return findAnchorTarget(x, y, extraMargin, false);
    }

    private int findAnchorTarget(int x, int y, int extraMargin, boolean allowBoundary) {
        Point2D p = new Point2D.Double(x, y);
        for (int i = shapes.size() - 1; i >= 0; i--) {
            ShapeRecord r = shapes.get(i);
            if (r == null || isConnectorTool(r.tool)) continue;
            if (!allowBoundary && r.tool == Tool.BOUNDARY) continue;
            try {
                if (r.shape.contains(p)) return i;
            } catch (Exception ignored) {
            }
            Shape pick = new BasicStroke(Math.max(6f, r.stroke + extraMargin)).createStrokedShape(r.shape);
            if (pick.contains(p)) return i;
        }
        return -1;
    }

    private ShapeRecord findShapeByLocalId(String localId) {
        if (localId == null) return null;
        for (ShapeRecord r : shapes) {
            if (r != null && localId.equals(r.localId)) return r;
        }
        return null;
    }

    private ShapeRecord findShapeByEntityId(String entityId) {
        if (entityId == null) return null;
        for (ShapeRecord r : shapes) {
            if (r != null && entityId.equals(r.entityId)) return r;
        }
        return null;
    }

    private String findEntityIdByLocalId(String localId) {
        ShapeRecord r = findShapeByLocalId(localId);
        return r != null ? r.entityId : null;
    }

    private void reanchorConnectorsFor(String localId) {
        if (localId == null) return;
        for (int i = 0; i < shapes.size(); i++) {
            ShapeRecord r = shapes.get(i);
            if (r == null || !isConnectorTool(r.tool)) continue;
            if (isManualConnector(r)) continue;
            if (!localId.equals(r.anchorFromId) && !localId.equals(r.anchorToId)) continue;

            ShapeRecord from = findShapeByLocalId(r.anchorFromId);
            ShapeRecord to = findShapeByLocalId(r.anchorToId);

            // Fallback for legacy/stale local anchor IDs: resolve using model connector endpoints.
            if ((from == null || to == null) && model != null && r.entityId != null) {
                ReMoDeLEntity connectorEntity = model.get(r.entityId);
                if (connectorEntity != null) {
                    Object fromIdObj = connectorEntity.get("fromId");
                    if (fromIdObj == null) fromIdObj = connectorEntity.get("from");
                    Object toIdObj = connectorEntity.get("toId");
                    if (toIdObj == null) toIdObj = connectorEntity.get("to");

                    if (from == null && fromIdObj != null) {
                        from = findShapeByEntityId(fromIdObj.toString());
                    }
                    if (to == null && toIdObj != null) {
                        to = findShapeByEntityId(toIdObj.toString());
                    }
                }
            }

            Point2D.Double start = new Point2D.Double(r.x1, r.y1);
            Point2D.Double end = new Point2D.Double(r.x2, r.y2);
            Shape connectorShape = new Line2D.Double(start.x, start.y, end.x, end.y);

            if (from != null && to != null) {
                Point2D.Double[] anchors = isSelfLoopTransition(r.tool, from, to)
                    ? computeSelfLoopAnchors(from)
                    : computeConnectorAnchors(from, to);
                start = anchors[0];
                end = anchors[1];
                connectorShape = isSelfLoopTransition(r.tool, from, to)
                    ? buildSelfLoopArc(from, start, end)
                    : new Line2D.Double(start.x, start.y, end.x, end.y);
            } else {
                if (from != null) {
                    Point2D.Double snapped = intersectShapeBoundary(from, getShapeCenter(from), end);
                    if (snapped != null) {
                        start = snapped;
                    }
                }
                if (to != null) {
                    Point2D.Double snapped = intersectShapeBoundary(to, getShapeCenter(to), start);
                    if (snapped != null) {
                        end = snapped;
                    }
                }
                connectorShape = new Line2D.Double(start.x, start.y, end.x, end.y);
            }

            ShapeRecord updated = new ShapeRecord(r.tool, connectorShape, r.color, r.stroke, start.x, start.y, end.x, end.y,
                r.text, r.font, r.entityId, r.localId, r.anchorFromId, r.anchorToId);
            shapes.set(i, updated);
        }
        redrawBuffer();
        repaint();
    }

    private static class ConnectorBranchGroup {
        final Tool tool;
        final String targetLocalId;
        final ShapeRecord targetShape;
        final java.util.List<ShapeRecord> members;
        final int order;

        ConnectorBranchGroup(Tool tool, String targetLocalId, ShapeRecord targetShape, java.util.List<ShapeRecord> members, int order) {
            this.tool = tool;
            this.targetLocalId = targetLocalId;
            this.targetShape = targetShape;
            this.members = members;
            this.order = order;
        }
    }

    private boolean isBranchableConnectorTool(Tool t) {
        return t == Tool.LINE || t == Tool.ARROW_FILLED || t == Tool.ARROW_EMPTY
            || t == Tool.ARROW_DIAMOND || t == Tool.ARROW_OPEN || t == Tool.IMPACT
            || t == Tool.REFERENCE;
    }

    private double angleDistance(double a, double b) {
        double diff = Math.abs(a - b) % (Math.PI * 2.0);
        return Math.min(diff, Math.PI * 2.0 - diff);
    }

    private java.util.List<ConnectorBranchGroup> collectBranchGroups() {
        java.util.Map<String, java.util.List<Integer>> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < shapes.size(); i++) {
            ShapeRecord r = shapes.get(i);
            if (r == null || !isBranchableConnectorTool(r.tool)) continue;
            if (r.anchorFromId == null || r.anchorToId == null) continue;
            if (r.anchorFromId.equals(r.anchorToId)) continue;
            ShapeRecord from = findShapeByLocalId(r.anchorFromId);
            ShapeRecord to = findShapeByLocalId(r.anchorToId);
            if (from == null || to == null) continue;
            String key = r.tool.name() + "|" + r.anchorToId;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        java.util.List<ConnectorBranchGroup> groups = new ArrayList<>();
        double threshold = Math.toRadians(CONNECTOR_BRANCH_ANGLE_THRESHOLD_DEGREES);

        for (java.util.Map.Entry<String, java.util.List<Integer>> entry : buckets.entrySet()) {
            java.util.List<Integer> indices = entry.getValue();
            if (indices.size() < 2) continue;

            java.util.List<ShapeRecord> members = new ArrayList<>();
            for (Integer index : indices) members.add(shapes.get(index));

            ShapeRecord first = members.get(0);
            ShapeRecord targetShape = findShapeByLocalId(first.anchorToId);
            if (targetShape == null) continue;
            Point2D.Double targetCenter = getShapeCenter(targetShape);

            double sumSin = 0.0;
            double sumCos = 0.0;
            java.util.List<Double> angles = new ArrayList<>();
            for (ShapeRecord member : members) {
                ShapeRecord sourceShape = findShapeByLocalId(member.anchorFromId);
                if (sourceShape == null) continue;
                Point2D.Double sourceCenter = getShapeCenter(sourceShape);
                double angle = Math.atan2(sourceCenter.y - targetCenter.y, sourceCenter.x - targetCenter.x);
                angles.add(angle);
                sumSin += Math.sin(angle);
                sumCos += Math.cos(angle);
            }

            if (angles.size() < 2) continue;
            double meanAngle = Math.atan2(sumSin, sumCos);
            double maxDiff = 0.0;
            for (double angle : angles) {
                maxDiff = Math.max(maxDiff, angleDistance(angle, meanAngle));
            }
            if (maxDiff > threshold) continue;

            int order = shapes.size();
            for (Integer index : indices) {
                if (index < order) order = index;
            }
            groups.add(new ConnectorBranchGroup(first.tool, first.anchorToId, targetShape, members, order));
        }

        groups.sort(java.util.Comparator.comparingInt(g -> g.order));
        return groups;
    }

    private void drawBranchConnectorGroup(Graphics2D g, ConnectorBranchGroup group) {
        if (group == null || group.members == null || group.members.size() < 2 || group.targetShape == null) return;

        Point2D.Double targetCenter = getShapeCenter(group.targetShape);
        java.util.List<ShapeRecord> members = new ArrayList<>(group.members);
        members.sort((a, b) -> {
            ShapeRecord as = findShapeByLocalId(a.anchorFromId);
            ShapeRecord bs = findShapeByLocalId(b.anchorFromId);
            Point2D.Double ac = as == null ? null : getShapeCenter(as);
            Point2D.Double bc = bs == null ? null : getShapeCenter(bs);
            double aa = ac == null ? 0.0 : Math.atan2(ac.y - targetCenter.y, ac.x - targetCenter.x);
            double ba = bc == null ? 0.0 : Math.atan2(bc.y - targetCenter.y, bc.x - targetCenter.x);
            return Double.compare(aa, ba);
        });

        java.util.List<Point2D.Double> sourceCenters = new ArrayList<>();
        java.util.List<ShapeRecord> sourceShapes = new ArrayList<>();
        for (ShapeRecord member : members) {
            ShapeRecord sourceShape = findShapeByLocalId(member.anchorFromId);
            if (sourceShape == null) continue;
            sourceShapes.add(sourceShape);
            sourceCenters.add(getShapeCenter(sourceShape));
        }
        if (sourceCenters.size() < 2) return;

        double averageSourceY = 0.0;
        for (Point2D.Double p : sourceCenters) averageSourceY += p.y;
        averageSourceY /= sourceCenters.size();

        double direction = averageSourceY >= targetCenter.y ? 1.0 : -1.0;
        double junctionY = targetCenter.y + direction * CONNECTOR_BRANCH_JUNCTION_DISTANCE;

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (Point2D.Double p : sourceCenters) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
        }
        double padding = Math.max(16.0, CONNECTOR_BRANCH_SPACING * 2.0);
        double barLeft = minX - padding;
        double barRight = maxX + padding;
        double trunkX = targetCenter.x;

        ShapeRecord representative = members.get(members.size() - 1);
        ShapeRecord targetSource = group.targetShape;
        Point2D.Double targetAnchor = intersectShapeBoundary(targetSource, targetCenter, new Point2D.Double(trunkX, junctionY));
        if (targetAnchor == null) targetAnchor = new Point2D.Double(trunkX, targetCenter.y);

        Color connectorColor = representative.color != null ? representative.color : Color.BLACK;
        g.setColor(connectorColor);

        // Draw the main trunk from the target to the branch bar.
        Point2D.Double trunkBase = computeArrowBase(targetAnchor.x, targetAnchor.y, trunkX, junctionY, representative.stroke);
        g.draw(new Line2D.Double(targetAnchor.x, targetAnchor.y, trunkBase.x, trunkBase.y));
        drawArrowHead(g, targetAnchor.x, targetAnchor.y, trunkX, junctionY, representative.tool);

        // Draw the branch bar.
        g.draw(new Line2D.Double(barLeft, junctionY, barRight, junctionY));

        // Draw vertical drops from the bar to each source shape.
        for (int i = 0; i < sourceCenters.size(); i++) {
            Point2D.Double sourceCenter = sourceCenters.get(i);
            ShapeRecord sourceShape = sourceShapes.get(i);
            Point2D.Double sourceAnchor = intersectShapeBoundary(sourceShape, sourceCenter, new Point2D.Double(sourceCenter.x, junctionY));
            if (sourceAnchor == null) sourceAnchor = sourceCenter;
            g.draw(new Line2D.Double(sourceAnchor.x, sourceAnchor.y, sourceAnchor.x, junctionY));
        }
    }

    private void drawConnectorRecords(Graphics2D g) {
        java.util.List<ConnectorBranchGroup> groups = collectBranchGroups();
        java.util.Set<ShapeRecord> grouped = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ConnectorBranchGroup group : groups) {
            grouped.addAll(group.members);
        }

        for (ConnectorBranchGroup group : groups) {
            drawBranchConnectorGroup(g, group);
        }

        for (ShapeRecord r : shapes) {
            if (r == null || !isConnectorTool(r.tool) || grouped.contains(r)) continue;
            drawRecord(g, r, false);
        }
    }

    private boolean isManualConnector(ReMoDeLEntity e) {
        if (e == null) return false;
        Object v = e.get("manualPosition");
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return false;
    }

    private boolean isManualConnector(ShapeRecord r) {
        return r != null && r.anchorFromId == null && r.anchorToId == null;
    }

    private ShapeRecord shapeFromConnector(ReMoDeLEntity connector, Map<String, ReMoDeLEntity> entityIndex) {
        if (connector == null) return null;
        Object fromObj = connector.get("fromId");
        Object toObj = connector.get("toId");
        if (fromObj == null || toObj == null) {
            fromObj = connector.get("from");
            toObj = connector.get("to");
        }
        String fromId = fromObj != null ? fromObj.toString() : null;
        String toId = toObj != null ? toObj.toString() : null;

        ReMoDeLEntity fromEntity = fromId != null ? entityIndex.get(fromId) : null;
        ReMoDeLEntity toEntity = toId != null ? entityIndex.get(toId) : null;
        ShapeRecord fromShape = fromEntity != null ? shapeFromEntity(fromEntity) : null;
        ShapeRecord toShape = toEntity != null ? shapeFromEntity(toEntity) : null;

        String typeStr = null;
        Object typeObj = connector.get("shapeType");
        if (typeObj == null) typeObj = connector.get("type");
        if (typeObj != null) typeStr = typeObj.toString();

        Tool tool = Tool.ARROW_OPEN;
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                tool = Tool.valueOf(typeStr.trim().toUpperCase());
            } catch (Exception ignored) {
            }
        }

        int x1 = connector.get("x1") instanceof Number ? ((Number) connector.get("x1")).intValue() : 0;
        int y1 = connector.get("y1") instanceof Number ? ((Number) connector.get("y1")).intValue() : 0;
        int x2 = connector.get("x2") instanceof Number ? ((Number) connector.get("x2")).intValue() : x1 + 40;
        int y2 = connector.get("y2") instanceof Number ? ((Number) connector.get("y2")).intValue() : y1 + 40;

        Point2D.Double start = new Point2D.Double(x1, y1);
        Point2D.Double end = new Point2D.Double(x2, y2);
        if (fromShape != null && toShape != null) {
            Point2D.Double[] anchors = isSelfLoopTransition(tool, fromShape, toShape)
                ? computeSelfLoopAnchors(fromShape)
                : computeConnectorAnchors(fromShape, toShape);
            start = anchors[0];
            end = anchors[1];
        } else {
            if (fromShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(fromShape, getShapeCenter(fromShape), end);
                if (snapped != null) start = snapped;
            }
            if (toShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(toShape, getShapeCenter(toShape), start);
                if (snapped != null) end = snapped;
            }
        }

        int rgb = connector.get("colorRGB") instanceof Number ? ((Number) connector.get("colorRGB")).intValue() : Color.BLACK.getRGB();
        Color c = new Color(rgb, true);
        float sWidth = connector.get("strokeWidth") instanceof Number ? ((Number) connector.get("strokeWidth")).floatValue() : strokeWidth;

        String text = connector.get("text") instanceof String ? (String) connector.get("text") : null;
        text = getConnectorLabel(tool, text);
        Shape connectorShape = (fromShape != null && toShape != null && isSelfLoopTransition(tool, fromShape, toShape))
            ? buildSelfLoopArc(fromShape, start, end)
            : new Line2D.Double(start.x, start.y, end.x, end.y);
        return new ShapeRecord(tool, connectorShape, c, sWidth, start.x, start.y, end.x, end.y, text, null,
            connector.getId(), connector.getId(), fromShape != null ? fromShape.localId : null, toShape != null ? toShape.localId : null);
    }

    private Point2D.Double[] computeConnectorAnchors(ShapeRecord fromShape, ShapeRecord toShape) {
        Point2D.Double fromCenter = getShapeCenter(fromShape);
        Point2D.Double toCenter = getShapeCenter(toShape);

        Point2D.Double start = intersectShapeBoundary(fromShape, fromCenter, toCenter);
        Point2D.Double end = intersectShapeBoundary(toShape, toCenter, fromCenter);

        if (start == null) start = fromCenter;
        if (end == null) end = toCenter;
        return new Point2D.Double[] { start, end };
    }

    private Point2D.Double[] computeSelfLoopAnchors(ShapeRecord shape) {
        Rectangle2D bounds = shape.shape.getBounds2D();
        Point2D.Double center = getShapeCenter(shape);

        double dx = Math.max(20.0, bounds.getWidth() * 1.2);
        double dy = Math.max(20.0, bounds.getHeight() * 1.2);

        Point2D.Double towardRight = new Point2D.Double(center.x + dx, center.y);
        Point2D.Double towardBottom = new Point2D.Double(center.x, center.y + dy);

        Point2D.Double start = intersectShapeBoundary(shape, center, towardRight);
        Point2D.Double end = intersectShapeBoundary(shape, center, towardBottom);

        if (start == null) {
            start = new Point2D.Double(bounds.getMaxX(), bounds.getCenterY());
        }
        if (end == null) {
            end = new Point2D.Double(bounds.getCenterX(), bounds.getMaxY());
        }
        return new Point2D.Double[] { start, end };
    }

    private Shape buildSelfLoopArc(ShapeRecord shape, Point2D.Double start, Point2D.Double end) {
        // Build a clean anchor-locked 3/4 ellipse:
        // start at right-edge anchor, sweep clockwise, end at bottom-edge anchor.
        double rx = Math.max(8.0, Math.abs(start.x - end.x));
        double ry = Math.max(8.0, Math.abs(end.y - start.y));

        // Place ellipse center so start is the top of the ellipse and end is the left side.
        double cx = start.x;
        double cy = end.y;

        double x = cx - rx;
        double y = cy - ry;
        return new Arc2D.Double(x, y, rx * 2.0, ry * 2.0, 90.0, -270.0, Arc2D.OPEN);
    }

    private Point2D.Double getShapeCenter(ShapeRecord r) {
        Rectangle2D b = r.shape.getBounds2D();
        return new Point2D.Double(b.getCenterX(), b.getCenterY());
    }

    private Point2D.Double intersectShapeBoundary(ShapeRecord r, Point2D.Double from, Point2D.Double to) {
        if (r == null || r.shape == null || from == null || to == null) return null;
        Rectangle2D b = r.shape.getBounds2D();
        if (r.tool == Tool.ACTOR) {
            b = actorAnchorBounds(r);
        }
        if (b.getWidth() <= 0 || b.getHeight() <= 0) return null;

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return new Point2D.Double(from.x, from.y);

        if (r.tool == Tool.OVAL) {
            // Ellipse: solve parametric intersection
            double rx = b.getWidth() / 2.0;
            double ry = b.getHeight() / 2.0;
            if (rx <= 0 || ry <= 0) return new Point2D.Double(from.x, from.y);
            double t = 1.0 / Math.sqrt((dx * dx) / (rx * rx) + (dy * dy) / (ry * ry));
            return new Point2D.Double(from.x + dx * t, from.y + dy * t);
        }

        // default: rectangle/rounded/state/text bounds intersection
        double minX = b.getMinX();
        double maxX = b.getMaxX();
        double minY = b.getMinY();
        double maxY = b.getMaxY();

        Point2D.Double best = null;
        double bestT = Double.POSITIVE_INFINITY;

        if (Math.abs(dx) > 1e-6) {
            double t1 = (minX - from.x) / dx;
            double y1 = from.y + t1 * dy;
            if (t1 > 0 && y1 >= minY && y1 <= maxY && t1 < bestT) {
                bestT = t1;
                best = new Point2D.Double(minX, y1);
            }
            double t2 = (maxX - from.x) / dx;
            double y2 = from.y + t2 * dy;
            if (t2 > 0 && y2 >= minY && y2 <= maxY && t2 < bestT) {
                bestT = t2;
                best = new Point2D.Double(maxX, y2);
            }
        }

        if (Math.abs(dy) > 1e-6) {
            double t3 = (minY - from.y) / dy;
            double x3 = from.x + t3 * dx;
            if (t3 > 0 && x3 >= minX && x3 <= maxX && t3 < bestT) {
                bestT = t3;
                best = new Point2D.Double(x3, minY);
            }
            double t4 = (maxY - from.y) / dy;
            double x4 = from.x + t4 * dx;
            if (t4 > 0 && x4 >= minX && x4 <= maxX && t4 < bestT) {
                bestT = t4;
                best = new Point2D.Double(x4, maxY);
            }
        }

        return best;
    }

    private Rectangle2D actorAnchorBounds(ShapeRecord r) {
        Rectangle2D figureBounds = r.shape != null ? r.shape.getBounds2D() : null;
        if (figureBounds == null) return null;

        String label = r.text != null ? r.text.split("\\R", 2)[0] : "Actor";
        Font font = normalizeTextFont(r.font);
        FontMetrics fm = getFontMetrics(font);
        double labelSpace = fm != null ? fm.getHeight() + 6.0 : 22.0;
        double labelWidth = fm != null ? fm.stringWidth(label) + 12.0 : figureBounds.getWidth();
        double anchorWidth = Math.max(figureBounds.getWidth(), labelWidth);
        double anchorX = figureBounds.getCenterX() - anchorWidth / 2.0;

        return new Rectangle2D.Double(
            anchorX,
            figureBounds.getY(),
            anchorWidth,
            figureBounds.getHeight() + labelSpace
        );
    }

    private ShapeRecord shapeFromEntity(ReMoDeLEntity e) {
        if (e == null) return null;
        String type = e.getType();
        if ("text".equalsIgnoreCase(type)) {
            Object ox1 = e.get("x1"); Object oy1 = e.get("y1"); Object ox2 = e.get("x2"); Object oy2 = e.get("y2");
            int x1 = ox1 instanceof Number ? ((Number)ox1).intValue() : 0;
            int y1 = oy1 instanceof Number ? ((Number)oy1).intValue() : 0;
            int x2 = ox2 instanceof Number ? ((Number)ox2).intValue() : x1 + 80;
            int y2 = oy2 instanceof Number ? ((Number)oy2).intValue() : y1 + 30;
            String txt = e.get("text") instanceof String ? (String)e.get("text") : "";
            String fontName = e.get("fontName") instanceof String ? (String)e.get("fontName") : "SansSerif";
            int fontStyle = e.get("fontStyle") instanceof Number ? ((Number)e.get("fontStyle")).intValue() : Font.PLAIN;
            int rgb = e.get("colorRGB") instanceof Number ? ((Number)e.get("colorRGB")).intValue() : Color.BLACK.getRGB();
            Font f = normalizeTextFont(new Font(fontName, fontStyle, DEFAULT_TEXT_FONT_SIZE));
            Color c = new Color(rgb, true);
            int w = Math.max(4, x2 - x1);
            int h = Math.max(4, y2 - y1);
            return ShapeRecord.textRecord(txt, f, c, strokeWidth, x1, y1, w, h, e.getId());
        }
        // non-text shapes: support a shapeType property for round-trip with the model
        Object ox1 = e.get("x1"); Object oy1 = e.get("y1"); Object ox2 = e.get("x2"); Object oy2 = e.get("y2");
        int x1 = ox1 instanceof Number ? ((Number)ox1).intValue() : 10;
        int y1 = oy1 instanceof Number ? ((Number)oy1).intValue() : 10;
        int x2 = ox2 instanceof Number ? ((Number)ox2).intValue() : x1 + 80;
        int y2 = oy2 instanceof Number ? ((Number)oy2).intValue() : y1 + 40;
        // determine tool from stored shapeType (fallback to RECTANGLE)
        String shapeTypeStr = e.get("shapeType") instanceof String ? (String)e.get("shapeType") : null;
        Tool t = Tool.RECTANGLE;
        if (shapeTypeStr != null) {
            try {
                t = Tool.valueOf(shapeTypeStr.toUpperCase());
            } catch (Exception ex) {
                // ignore and keep default
            }
        }
        // color and stroke (optional)
        int rgb = e.get("colorRGB") instanceof Number ? ((Number)e.get("colorRGB")).intValue() : Color.BLACK.getRGB();
        Color col = new Color(rgb, true);
        float sWidth = e.get("strokeWidth") instanceof Number ? ((Number)e.get("strokeWidth")).floatValue() : strokeWidth;

        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1);
        int rh = Math.abs(y2 - y1);
        Shape s = null;
        String txt = e.get("text") instanceof String ? (String) e.get("text") : null;
        switch (t) {
            case LINE:
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_OPEN:
            case IMPACT:
            case REFERENCE:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case OVAL:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                break;
            case ROUNDED_RECTANGLE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                break;
            case ACTOR:
                s = buildActorShape(rx, ry, rw, rh);
                break;
            case RECTANGLE:
            case OBJECT_TYPE:
            default:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                break;
        }
        return new ShapeRecord(t, s, col, sWidth, x1, y1, x2, y2, txt, null, e.getId());
    }

    private ReMoDeLEntity entityFromShape(ShapeRecord r) {
        if (r == null) return null;
        ReMoDeLEntity ent = new ReMoDeLEntity(r.entityId);
        if (r.tool == Tool.TEXT) {
            ent.setType("text");
            ent.put("x1", (int) Math.round(r.x1));
            ent.put("y1", (int) Math.round(r.y1));
            ent.put("x2", (int) Math.round(r.x2));
            ent.put("y2", (int) Math.round(r.y2));
            ent.put("text", r.text != null ? r.text : "");
            Font font = normalizeTextFont(r.font);
            ent.put("fontName", font.getName());
            ent.put("fontStyle", font.getStyle());
            ent.put("fontSize", font.getSize());
            if (r.color != null) ent.put("colorRGB", r.color.getRGB());
            return ent;
        }
        ent.setType("shape");
        ent.put("x1", (int) Math.round(r.x1));
        ent.put("y1", (int) Math.round(r.y1));
        ent.put("x2", (int) Math.round(r.x2));
        ent.put("y2", (int) Math.round(r.y2));
        // store the tool/shape type so we can reconstruct the exact visual later
        if (r.tool != null) ent.put("shapeType", r.tool.name());
        if (r.color != null) ent.put("colorRGB", r.color.getRGB());
        if (r.text != null) ent.put("text", r.text);
        ent.put("strokeWidth", r.stroke);
        return ent;
    }

    // Undoable edit for moving/resizing a shape (stores before/after ShapeRecord)
    private class MoveEdit extends AbstractUndoableEdit {
        private final int index;
        private final ShapeRecord before;
        private ShapeRecord after;

        MoveEdit(int index, ShapeRecord before) {
            this.index = index;
            this.before = before;
        }

        void setAfter(ShapeRecord after) {
            this.after = after;
        }

        @Override
        public void undo() {
            super.undo();
            if (index >= 0 && index < shapes.size()) {
                shapes.set(index, copyShapeRecord(before));
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public void redo() {
            super.redo();
            if (after != null && index >= 0 && index < shapes.size()) {
                shapes.set(index, copyShapeRecord(after));
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public String getPresentationName() {
            return "Move/Resize";
        }
    }

    // Undoable edit for creating a text shape
    private class TextCreateEdit extends AbstractUndoableEdit {
        private final int index;
        private final ShapeRecord record;
        private final ReMoDeLEntity entityCopy; // optional model entity snapshot

        TextCreateEdit(int index, ShapeRecord record) {
            this(index, record, null);
        }

        TextCreateEdit(int index, ShapeRecord record, ReMoDeLEntity entityCopy) {
            this.index = index;
            this.record = record;
            this.entityCopy = entityCopy;
        }

        @Override
        public void undo() {
            super.undo();
            // If backed by model, remove from model (listener will update shapes)
            if (record != null && record.entityId != null && model != null) {
                model.removeEntity(record.entityId);
                return;
            }
            if (index >= 0 && index < shapes.size()) {
                shapes.remove(index);
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public void redo() {
            super.redo();
            if (record != null && record.entityId != null && model != null && entityCopy != null) {
                // re-add entity to model; model listener will rebuild shapes
                model.addEntity(entityCopy.copy());
                return;
            }
            if (index >= 0 && index <= shapes.size()) {
                shapes.add(index, copyShapeRecord(record));
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public String getPresentationName() { return "Add Text"; }
    }

    // Undoable edit for editing text content/font/color
    private class TextEdit extends AbstractUndoableEdit {
        private final int index;
        private final ShapeRecord before;
        private final ShapeRecord after;

        TextEdit(int index, ShapeRecord before, ShapeRecord after) {
            this.index = index;
            this.before = before;
            this.after = after;
        }

        @Override
        public void undo() {
            super.undo();
            // if model-backed, update model entity instead (listener will rebuild)
            String eid = before != null ? before.entityId : null;
            if (eid != null && model != null) {
                model.updateEntity(entityFromShape(before));
                return;
            }
            if (index >= 0 && index < shapes.size()) {
                shapes.set(index, copyShapeRecord(before));
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public void redo() {
            super.redo();
            String eid = after != null ? after.entityId : null;
            if (eid != null && model != null) {
                model.updateEntity(entityFromShape(after));
                return;
            }
            if (index >= 0 && index < shapes.size()) {
                shapes.set(index, copyShapeRecord(after));
                redrawBuffer();
                repaint();
            }
        }

        @Override
        public String getPresentationName() { return "Edit Text"; }
    }

    // Undoable edit for creating any non-text shape/connector.
    private class ShapeCreateEdit extends AbstractUndoableEdit {
        private final ShapeRecord record;
        private final ReMoDeLEntity entityCopy;

        ShapeCreateEdit(ShapeRecord record, ReMoDeLEntity entityCopy) {
            this.record = record;
            this.entityCopy = entityCopy;
        }

        @Override
        public void undo() {
            super.undo();
            if (entityCopy != null && model != null && entityCopy.getId() != null) {
                model.removeEntity(entityCopy.getId());
                return;
            }

            if (record == null) return;
            String localId = record.localId;
            for (int i = 0; i < shapes.size(); i++) {
                ShapeRecord candidate = shapes.get(i);
                boolean sameId = candidate != null
                    && ((candidate.localId == null && localId == null)
                        || (candidate.localId != null && candidate.localId.equals(localId)));
                if (sameId) {
                    shapes.remove(i);
                    redrawBuffer();
                    repaint();
                    return;
                }
            }
        }

        @Override
        public void redo() {
            super.redo();
            if (entityCopy != null && model != null) {
                model.addEntity(entityCopy.copy());
                return;
            }

            if (record == null) return;
            shapes.add(copyShapeRecord(record));
            if (!isConnectorTool(record.tool)) sortShapesByArea();
            redrawBuffer();
            repaint();
        }

        @Override
        public String getPresentationName() { return "Create Shape"; }
    }

    // tools
    public enum Tool {
        SELECT, PAN, DELETE, FREEHAND, 
        LINE,                   // General association
        ARROW_FILLED,           // Dataflow (Task Model)
        ARROW_EMPTY,            // Generalisation
        ARROW_DIAMOND,          // Composition
        ARROW_OPEN,             // Event/Transition
        IMPACT, REFERENCE, ENACTS,
        OVAL, RECTANGLE, ROUNDED_RECTANGLE, BOUNDARY, OBJECT_TYPE,
        TEXT, STATE, ACTOR, SYSTEM, AUTHORISATION, 
        INITIAL_TRANSITION, FINAL_TRANSITION
    }
    private Tool currentTool = Tool.SELECT;

    // stored shapes
    private final java.util.List<ShapeRecord> shapes = new ArrayList<>();
    private ShapeRecord preview = null;

    // pending connector creation (first click source, second click target)
    private PendingConnector pendingConnector = null;

    // optional backing model and mapping from entity id -> shape index
    private ReMoDeLModel model = null;
    private final java.util.Map<String, Integer> idToIndex = new java.util.HashMap<>();

    // Model getter for external access (e.g., for save/export)
    public ReMoDeLModel getModel() {
        return model;
    }

    /**
     * Returns an export-ready model.
     * If the canvas has no backing model, build a temporary snapshot model
     * from current non-connector shapes and connector anchors.
     */
    public ReMoDeLModel getExportModel() {
        if (model != null) return model;

        ReMoDeLModel snapshot = new ReMoDeLModel();
        Map<String, String> localToEntity = new HashMap<>();

        for (ShapeRecord r : shapes) {
            if (r == null || isConnectorTool(r.tool)) continue;
            ReMoDeLEntity ent = entityFromShape(r);
            snapshot.addEntity(ent);
            if (r.localId != null) {
                localToEntity.put(r.localId, ent.getId());
            }
        }

        // Then: add connectors, resolving anchor references
        for (ShapeRecord r : shapes) {
            if (r == null || !isConnectorTool(r.tool)) continue;
            String fromId = localToEntity.get(r.anchorFromId);
            String toId = localToEntity.get(r.anchorToId);
            ReMoDeLEntity connector = new ReMoDeLEntity();
            connector.setType("connector");
            connector.put("shapeType", r.tool.name());
            connector.put("strokeWidth", r.stroke);
            if (r.color != null) connector.put("colorRGB", r.color.getRGB());
            if (r.text != null) connector.put("text", r.text);

            if (fromId != null && toId != null) {
                connector.put("fromId", fromId);
                connector.put("toId", toId);
                connector.put("manualPosition", false);
            } else {
                connector.put("manualPosition", true);
                connector.put("x1", (int) Math.round(r.x1));
                connector.put("y1", (int) Math.round(r.y1));
                connector.put("x2", (int) Math.round(r.x2));
                connector.put("y2", (int) Math.round(r.y2));
            }
            snapshot.addEntity(connector);
        }

        return snapshot;
    }

    public void setReferenceDefaultName(String name) {
        referenceDefaultName = name != null && !name.isBlank() ? name.trim() : "member";
    }

    public void setReferenceQualifier(String qualifier) {
        referenceQualifier = qualifier != null ? qualifier.trim() : "";
    }

    // selection/edit state
    private int selectedIndex = -1;
    private int activeLabelEditIndex = -1;
    private boolean draggingMove = false;
    private boolean resizing = false;
    private boolean draggingConnectorEndpoint = false;
    private int activeConnectorEndpoint = -1; // 0 start, 1 end
    private int activeHandle = -1; // 0..3 corners
    private boolean selectAllActive = false;
    private int pressX, pressY;
    private ShapeRecord clipboardRecord = null;
    private int pasteSerial = 0;
    private double zoomScale = 1.0;

    private static final int BASE_CANVAS_WIDTH = 1600;
    private static final int BASE_CANVAS_HEIGHT = 1200;
    private static final int CANVAS_EDGE_PADDING = 120;

    private static final double ZOOM_MIN = 0.25;
    private static final double ZOOM_MAX = 3.0;
    private static final double ZOOM_STEP = 1.15;

    private static final int HANDLE_SIZE = 10;
    private static final int HANDLE_HIT_MARGIN = 6;
    private static final int CONNECTOR_SNAP_MARGIN = 16;
    private static final int CONNECTOR_BRANCH_ANGLE_THRESHOLD_DEGREES = 15;
    private static final double CONNECTOR_BRANCH_JUNCTION_DISTANCE = 34.0;
    private static final double CONNECTOR_BRANCH_SPACING = 12.0;
    private static final double CROSSING_ENDPOINT_PADDING = 12.0;
    private static final double CROSSING_BRIDGE_RADIUS = 8.0;
    private static final int ENTITY_DEFAULT_WIDTH = 300;
    private static final int ENTITY_DEFAULT_HEIGHT = 100;
    private static final int ACTOR_DEFAULT_WIDTH = 100;
    private static final int ACTOR_DEFAULT_HEIGHT = 90;
    private static final int BOUNDARY_MARGIN = 24;
    private static final int BOUNDARY_CLICK_THRESHOLD = 18;
    private static final int BOUNDARY_MIN_WIDTH = 600;
    private static final int BOUNDARY_MIN_HEIGHT = 650;
    private static final double BOUNDARY_TAB_WIDTH = 300.0;
    private static final double BOUNDARY_TAB_HEIGHT = 70.0;
    private static final String DEFAULT_TEXT_FONT_NAME = "SansSerif";
    private static final int DEFAULT_TEXT_FONT_SIZE = 30;
    private static final int OBJECT_TYPE_MIN_ATTRIBUTES = 1;
    private static final int OBJECT_TYPE_MAX_ATTRIBUTES = 50;

    private int objectTypeAttributeCount = 1;

    private Font defaultTextFont() {
        return new Font(DEFAULT_TEXT_FONT_NAME, Font.PLAIN, DEFAULT_TEXT_FONT_SIZE);
    }

    private int toModelX(int viewX) {
        return (int) Math.round(viewX / zoomScale);
    }

    private int toModelY(int viewY) {
        return (int) Math.round(viewY / zoomScale);
    }

    private int toViewX(double modelX) {
        return (int) Math.round(modelX * zoomScale);
    }

    private int toViewY(double modelY) {
        return (int) Math.round(modelY * zoomScale);
    }

    private Rectangle zoomedBounds(double x, double y, double width, double height) {
        return new Rectangle(
            toViewX(x),
            toViewY(y),
            Math.max(1, (int) Math.round(width * zoomScale)),
            Math.max(1, (int) Math.round(height * zoomScale))
        );
    }

    private Rectangle2D computeContentBounds() {
        Rectangle2D union = null;
        for (ShapeRecord r : shapes) {
            if (r == null || r.shape == null) continue;
            Rectangle2D bounds = r.shape.getBounds2D();
            if (bounds == null) continue;
            if (union == null) {
                union = new Rectangle2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
            } else {
                Rectangle2D.union(union, bounds, union);
            }
        }
        if (union == null) {
            return new Rectangle2D.Double(0, 0, BASE_CANVAS_WIDTH, BASE_CANVAS_HEIGHT);
        }
        return new Rectangle2D.Double(
            Math.max(0, union.getX() - CANVAS_EDGE_PADDING),
            Math.max(0, union.getY() - CANVAS_EDGE_PADDING),
            union.getWidth() + CANVAS_EDGE_PADDING * 2.0,
            union.getHeight() + CANVAS_EDGE_PADDING * 2.0
        );
    }

    private Dimension getLogicalCanvasSize() {
        Rectangle2D bounds = computeContentBounds();
        int width = (int) Math.ceil(Math.max(BASE_CANVAS_WIDTH, bounds.getMaxX() + CANVAS_EDGE_PADDING));
        int height = (int) Math.ceil(Math.max(BASE_CANVAS_HEIGHT, bounds.getMaxY() + CANVAS_EDGE_PADDING));
        return new Dimension(Math.max(1, width), Math.max(1, height));
    }

    private Dimension getZoomedCanvasSize() {
        Dimension logical = getLogicalCanvasSize();
        int width = Math.max(1, (int) Math.round(logical.width * zoomScale));
        int height = Math.max(1, (int) Math.round(logical.height * zoomScale));
        return new Dimension(width, height);
    }

    private void updateCanvasExtent() {
        Dimension desired = getZoomedCanvasSize();
        if (!desired.equals(getPreferredSize())) {
            setPreferredSize(desired);
            revalidate();
        }
    }

    private void setZoom(double requested) {
        double clamped = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, requested));
        if (Math.abs(clamped - zoomScale) < 1e-9) return;
        double previous = zoomScale;
        zoomScale = clamped;
        firePropertyChange("zoomScale", previous, zoomScale);
        updateCanvasExtent();
        repaint();
    }

    public double getZoomScale() {
        return zoomScale;
    }

    public void zoomIn() {
        setZoom(zoomScale * ZOOM_STEP);
    }

    public void zoomOut() {
        setZoom(zoomScale / ZOOM_STEP);
    }

    public void resetZoom() {
        setZoom(1.0);
    }

    public int getObjectTypeAttributeCount() {
        return objectTypeAttributeCount;
    }

    public void setObjectTypeAttributeCount(int count) {
        int clamped = Math.max(OBJECT_TYPE_MIN_ATTRIBUTES, Math.min(OBJECT_TYPE_MAX_ATTRIBUTES, count));
        if (clamped == objectTypeAttributeCount) return;
        int previous = objectTypeAttributeCount;
        objectTypeAttributeCount = clamped;
        firePropertyChange("objectTypeAttributeCount", previous, objectTypeAttributeCount);
    }

    private String buildObjectTypeTemplate(int attributeCount) {
        int count = Math.max(OBJECT_TYPE_MIN_ATTRIBUTES, Math.min(OBJECT_TYPE_MAX_ATTRIBUTES, attributeCount));
        StringBuilder text = new StringBuilder("Object");
        for (int i = 1; i <= count; i++) {
            text.append("\nattribute").append(i);
        }
        return text.toString();
    }

    private int[] measureObjectTypeSize(String text, Font font) {
        FontMetrics fm = getFontMetrics(font != null ? font : defaultTextFont());
        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        if (lines.length == 0) lines = new String[] { "Object" };

        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, fm.stringWidth(line != null ? line : ""));
        }

        int nameHeight = fm.getHeight() + 6;
        int attrHeight = Math.max(1, lines.length - 1) * fm.getHeight();
        int width = Math.max(ENTITY_DEFAULT_WIDTH, maxWidth + 24);
        int height = Math.max(ENTITY_DEFAULT_HEIGHT, nameHeight + attrHeight + 14);
        return new int[] { width, height };
    }

    private static class ObjectTypeEditParts {
        final String name;
        final java.util.List<String> attributes;

        ObjectTypeEditParts(String name, java.util.List<String> attributes) {
            this.name = name;
            this.attributes = attributes;
        }
    }

    private ObjectTypeEditParts parseObjectTypeParts(String text) {
        String[] lines = text != null ? text.split("\\R", -1) : new String[0];
        String name = (lines.length > 0 && !lines[0].isBlank()) ? lines[0].trim() : "Object";
        java.util.List<String> attrs = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) continue;
            String[] chunks = line.split(";");
            for (String chunk : chunks) {
                String attr = chunk == null ? "" : chunk.trim();
                if (!attr.isEmpty()) attrs.add(attr);
            }
        }
        if (attrs.isEmpty()) attrs.add("");
        return new ObjectTypeEditParts(name, attrs);
    }

    private String buildObjectTypeLabel(String name, java.util.List<String> attributes) {
        String safeName = name != null && !name.isBlank() ? name.trim() : "Object";
        StringBuilder out = new StringBuilder(safeName);
        if (attributes != null) {
            for (String attr : attributes) {
                String line = attr != null ? attr.trim() : "";
                if (!line.isEmpty()) {
                    out.append("\n").append(line);
                }
            }
        }
        return out.toString();
    }

    private void rebuildObjectTypeAttributeRows(JPanel attributesPanel, java.util.List<JTextField> attributeFields, JDialog dialog) {
        attributesPanel.removeAll();
        for (int i = 0; i < attributeFields.size(); i++) {
            JTextField field = attributeFields.get(i);
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.add(new JLabel((i + 1) + "."), BorderLayout.WEST);
            row.add(field, BorderLayout.CENTER);
            JButton removeBtn = new JButton("-");
            removeBtn.setFocusable(false);
            final int removeIndex = i;
            removeBtn.addActionListener(e -> {
                if (attributeFields.size() <= 1) return;
                attributeFields.remove(removeIndex);
                rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, dialog);
                dialog.pack();
            });
            row.add(removeBtn, BorderLayout.EAST);
            attributesPanel.add(row);
            if (i < attributeFields.size() - 1) {
                attributesPanel.add(Box.createVerticalStrut(6));
            }
        }
        attributesPanel.revalidate();
        attributesPanel.repaint();
    }

    private String showObjectTypeEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Object Type", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Object Type", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        ObjectTypeEditParts initial = parseObjectTypeParts(sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.add(new JLabel("Object name:"), BorderLayout.WEST);
        JTextField nameField = new JTextField(initial.name, 24);
        nameRow.add(nameField, BorderLayout.CENTER);
        form.add(nameRow);
        form.add(Box.createVerticalStrut(10));

        JPanel attributesPanel = new JPanel();
        attributesPanel.setLayout(new BoxLayout(attributesPanel, BoxLayout.Y_AXIS));
        java.util.List<JTextField> attributeFields = new ArrayList<>();

        for (String attribute : initial.attributes) {
            attributeFields.add(new JTextField(attribute, 24));
        }
        rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, dialog);

        JScrollPane scrollPane = new JScrollPane(attributesPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Attributes"));
        scrollPane.setPreferredSize(new Dimension(420, 220));
        form.add(scrollPane);

        JPanel actionRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton addBtn = new JButton("Add Attribute");
        addBtn.addActionListener(e -> {
            attributeFields.add(new JTextField("", 24));
            rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, dialog);
            dialog.pack();
        });
        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        actionRow.add(addBtn);
        actionRow.add(cancelBtn);
        actionRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(actionRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            String objectName = nameField.getText() != null ? nameField.getText().trim() : "";
            java.util.List<String> attributes = new ArrayList<>();
            for (JTextField field : attributeFields) {
                String value = field.getText() != null ? field.getText().trim() : "";
                if (!value.isEmpty()) attributes.add(value);
            }
            if (objectName.isEmpty()) objectName = "Object";
            result[0] = buildObjectTypeLabel(objectName, attributes);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        if (!committed[0]) {
            return null;
        }
        return result[0];
    }

    private Font normalizeTextFont(Font source) {
        if (source == null) return defaultTextFont();
        return new Font(source.getName(), source.getStyle(), DEFAULT_TEXT_FONT_SIZE);
    }

    private Font zoomAwareEditorFont(Font source) {
        Font base = normalizeTextFont(source);
        float scaledSize = (float) Math.max(10.0, base.getSize2D() * zoomScale);
        return base.deriveFont(scaledSize);
    }

    // Fixed-size entities. Boundary remains resizable.
    private boolean isConstrainedSizeTool(Tool t) {
        return t == Tool.RECTANGLE
            || t == Tool.OVAL
            || t == Tool.ROUNDED_RECTANGLE
            || t == Tool.STATE
            || t == Tool.OBJECT_TYPE
            || t == Tool.SYSTEM
            || t == Tool.ACTOR;
    }

    // Fixed-size entities are always created/resized to a constant size per tool.
    private int[] enforceDefaultSize(Tool tool, int width, int height) {
        if (!isConstrainedSizeTool(tool)) return new int[] { width, height };
        if (tool == Tool.ACTOR) {
            return new int[] { ACTOR_DEFAULT_WIDTH, ACTOR_DEFAULT_HEIGHT };
        }
        return new int[] { ENTITY_DEFAULT_WIDTH, ENTITY_DEFAULT_HEIGHT };
    }

    private void beginLabelEdit(int index) {
        selectedIndex = index;
        activeLabelEditIndex = index;
        redrawBuffer();
        repaint();
    }

    private void endLabelEdit() {
        activeLabelEditIndex = -1;
        redrawBuffer();
        repaint();
    }

    private boolean shouldSuppressLabelPaint(ShapeRecord r) {
        return r != null
            && activeLabelEditIndex >= 0
            && activeLabelEditIndex < shapes.size()
            && shapes.get(activeLabelEditIndex) == r;
    }

    private void styleInlineEditor(JTextComponent comp) {
        if (comp == null) return;
        comp.setSelectionColor(new Color(66, 133, 244, 170));
        comp.setSelectedTextColor(Color.BLACK);
        comp.setCaretColor(Color.BLACK);
    }

    public DrawingCanvas() {
        setPreferredSize(new Dimension(BASE_CANVAS_WIDTH, BASE_CANVAS_HEIGHT));
        setBackground(Color.WHITE);
        setOpaque(true);
        initMouse();
        initKeyboardShortcuts();
        setFocusable(true);
        updateCanvasExtent();
    }

    private void initKeyboardShortcuts() {
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        InputMap inputMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask), "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undo();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcutMask), "redoAction");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask | InputEvent.SHIFT_DOWN_MASK), "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redo();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask), "copySelection");
        actionMap.put("copySelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedShape();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask), "pasteSelection");
        actionMap.put("pasteSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteClipboardShape();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcutMask), "cutSelection");
        actionMap.put("cutSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cutSelectedShape();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcutMask), "selectAllShapes");
        actionMap.put("selectAllShapes", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAllShapes();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteSelection");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSelection");
        actionMap.put("deleteSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelectedShape();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, shortcutMask), "zoomIn");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, shortcutMask), "zoomIn");
        actionMap.put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomIn();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, shortcutMask), "zoomOut");
        actionMap.put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomOut();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, shortcutMask), "zoomReset");
        actionMap.put("zoomReset", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                resetZoom();
            }
        });
    }

    private static class ShapeRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        final Tool tool;
        final Shape shape; // primary geometry (Line2D, Path2D, Rect/Ellipse)
        final Color color;
        final float stroke;
        // extra data for arrows and geometry
        final String entityId; // optional associated model entity id (if this shape is backed by ReMoDeLModel)
        final String localId; // stable id for non-model shapes/connectors
        final String anchorFromId; // connector source local id
        final String anchorToId; // connector target local id
        final double x1, y1, x2, y2;
        // text-specific
        final String text;
        final Font font;

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text, Font font) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, text, font, null);
        }

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text, Font font, String entityId) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, text, font, entityId, null, null, null);
        }

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text,
                    Font font, String entityId, String localId, String anchorFromId, String anchorToId) {
            this.tool = tool;
            this.shape = shape;
            this.color = color;
            this.stroke = stroke;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.text = text; this.font = font;
            this.entityId = entityId;
            this.localId = localId != null ? localId : (entityId != null ? entityId : java.util.UUID.randomUUID().toString());
            this.anchorFromId = anchorFromId;
            this.anchorToId = anchorToId;
        }

        // convenience constructor for non-text shapes
        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, null, null);
        }

        // convenience factory for text records (bounding rect + text/font)
        static ShapeRecord textRecord(String text, Font font, Color color, float stroke, double x, double y, double w, double h) {
            return textRecord(text, font, color, stroke, x, y, w, h, null);
        }

        static ShapeRecord stateRecord(String stateName, Font font, Color color, float stroke, double x, double y, double w, double h) {
            double arc = Math.max(20, Math.min(w, h) / 4.0);
            Shape rect = new RoundRectangle2D.Double(x, y, w, h, arc, arc);
            return new ShapeRecord(Tool.STATE, rect, color, stroke, x, y, x + w, y + h, stateName, font, null);
        }

        static ShapeRecord textRecord(String text, Font font, Color color, float stroke, double x, double y, double w, double h, String entityId) {
            Shape rect = new Rectangle2D.Double(x, y, w, h);
            return new ShapeRecord(Tool.TEXT, rect, color, stroke, x, y, x + w, y + h, text, font, entityId);
        }
    }

    private static class CanvasSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        private final java.util.List<ShapeRecord> shapes;

        CanvasSnapshot(java.util.List<ShapeRecord> shapes) {
            this.shapes = shapes;
        }
    }

    // tracks in-progress connector selection (source)
    private static class PendingConnector {
        final Tool tool;
        final String fromEntityId;
        final Point2D fromPoint;

        PendingConnector(Tool tool, String fromEntityId, Point2D fromPoint) {
            this.tool = tool;
            this.fromEntityId = fromEntityId;
            this.fromPoint = fromPoint;
        }
    }

    private void ensureBuffer() {

        Dimension logicalSize = getLogicalCanvasSize();
        if (buf == null || buf.getWidth() != logicalSize.width || buf.getHeight() != logicalSize.height) {
            redrawBuffer();
        }
    }

    private void redrawBuffer() {

        Dimension logicalSize = getLogicalCanvasSize();
        BufferedImage newBuf = new BufferedImage(Math.max(1, logicalSize.width), Math.max(1, logicalSize.height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = newBuf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // clear background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newBuf.getWidth(), newBuf.getHeight());
        // redraw existing shapes into new buffer
        for (ShapeRecord r : shapes) {
            if (r == null || isConnectorTool(r.tool)) continue;
            drawRecord(g, r, false);
        }
        drawConnectorRecords(g);
        g.dispose();
        buf = newBuf;
        updateCanvasExtent();
    }

    private void initMouse() {

        MouseAdapter ma = new MouseAdapter() {
            private GeneralPath freePath;

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                selectAllActive = false;
                pressX = lastX = toModelX(e.getX());
                pressY = lastY = toModelY(e.getY());
                statusConsumer.accept("Drawing...");

                if (currentTool == Tool.FREEHAND) {
                    freePath = new GeneralPath();
                    freePath.moveTo(lastX, lastY);
                    preview = new ShapeRecord(Tool.FREEHAND, freePath, drawColor, strokeWidth, lastX, lastY, lastX, lastY);
                    repaint();
                    return;
                }

                if (currentTool == Tool.PAN) {
                    preview = null;
                    int hit = hitTest(lastX, lastY);
                    if (hit >= 0) {
                        ShapeRecord selected = shapes.get(hit);
                        if (!isConnectorTool(selected.tool)) {
                            selectedIndex = hit;
                            draggingMove = true;
                            ShapeRecord before = copyShapeRecord(selected);
                            currentMoveEdit = new MoveEdit(selectedIndex, before);
                            repaint();
                        } else {
                            selectedIndex = -1;
                            repaint();
                        }
                    } else {
                        selectedIndex = -1;
                        repaint();
                    }
                } else if (currentTool == Tool.SELECT) {
                    // Select mode should never create drawing previews.
                    preview = null;
                    // hit-test shapes from top-most to bottom
                    int hit = hitTest(lastX, lastY);
                    if (hit >= 0) {
                        // if double-clicked, start inline editing
                        if (e.getClickCount() == 2) {
                            ShapeRecord sr = shapes.get(hit);
                            // TEXT items use the existing text editor
                            if (sr.tool == Tool.TEXT) {
                                startEditingText(hit);
                                return;
                            }
                                // Shape items (RECTANGLE, OVAL, ROUNDED_RECTANGLE, STATE, ACTOR, SYSTEM) get label editing
                                else if (sr.tool == Tool.RECTANGLE || sr.tool == Tool.OVAL || 
                                    sr.tool == Tool.ROUNDED_RECTANGLE || sr.tool == Tool.STATE
                                    || sr.tool == Tool.ACTOR || sr.tool == Tool.SYSTEM
                                    || sr.tool == Tool.BOUNDARY) {
                                startEditingShapeLabel(hit);
                                return;
                            } else if (sr.tool == Tool.OBJECT_TYPE) {
                                startEditingObjectTypeLabel(hit);
                                return;
                            } else if (sr.tool == Tool.IMPACT || sr.tool == Tool.ARROW_OPEN || sr.tool == Tool.ARROW_FILLED 
                                    || sr.tool == Tool.ARROW_EMPTY || sr.tool == Tool.ARROW_DIAMOND 
                                    || sr.tool == Tool.INITIAL_TRANSITION || sr.tool == Tool.FINAL_TRANSITION) {
                                startEditingImpactLabel(hit);
                                return;
                            } else if (sr.tool == Tool.REFERENCE) {
                                startEditingReferenceLabel(hit);
                                return;
                            }
                        }
                        selectedIndex = hit;
                        ShapeRecord selected = shapes.get(selectedIndex);
                        // check if clicked on an edit handle
                        if (isConnectorTool(selected.tool)) {
                            activeConnectorEndpoint = connectorHandleHit(selected, lastX, lastY);
                            if (activeConnectorEndpoint >= 0) {
                                draggingConnectorEndpoint = true;
                            } else {
                                draggingMove = true;
                            }
                        } else {
                            if (isConstrainedSizeTool(selected.tool)) {
                                activeHandle = -1;
                                resizing = false;
                                draggingMove = true;
                            } else {
                                Rectangle2D bounds = getShapeBounds(selected);
                                activeHandle = handleHit(bounds, lastX, lastY);
                                if (activeHandle >= 0) {
                                    resizing = true;
                                } else {
                                    draggingMove = true;
                                }
                            }
                        }
                        // start a grouped move/resize edit: capture the original record
                        ShapeRecord before = copyShapeRecord(shapes.get(selectedIndex));
                        currentMoveEdit = new MoveEdit(selectedIndex, before);
                        repaint();
                    } else {
                        // clicked empty area -> clear selection
                        selectedIndex = -1;
                        repaint();
                    }
                }

                // only drawing tools should set a preview (not SELECT/TEXT)
                if (currentTool != Tool.TEXT && currentTool != Tool.SELECT && currentTool != Tool.PAN) {
                    preview = createPreview(lastX, lastY, lastX, lastY);
                } else {
                    preview = null;
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int x = toModelX(e.getX()), y = toModelY(e.getY());
                // If TEXT tool is active, dragging should not create/update previews or shapes.
                if (currentTool == Tool.TEXT) {
                    lastX = x; lastY = y;
                    return;
                }
                if (currentTool == Tool.FREEHAND && preview != null && preview.shape instanceof GeneralPath) {
                    ((GeneralPath) preview.shape).lineTo(x, y);
                    lastX = x; lastY = y;
                    repaint();
                    return;
                }

                if (currentTool == Tool.PAN) {
                    if (selectedIndex >= 0 && draggingMove) {
                        ShapeRecord sel = shapes.get(selectedIndex);
                        if (!isConnectorTool(sel.tool)) {
                            int dx = x - lastX, dy = y - lastY;
                            if (sel.tool == Tool.TEXT) {
                                double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                            } else {
                                Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                        sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                            }
                            redrawBuffer();
                            repaint();
                        }
                    }
                    lastX = x; lastY = y;
                    return;
                }

                if (currentTool == Tool.SELECT) {
                    if (selectedIndex >= 0) {
                        ShapeRecord sel = shapes.get(selectedIndex);
                        int dx = x - lastX, dy = y - lastY;

                        if (draggingConnectorEndpoint && isConnectorTool(sel.tool)) {
                            ShapeRecord nr = updateConnectorEndpointRecord(sel, activeConnectorEndpoint, x, y);
                            if (nr != null) {
                                if (sel.entityId != null && model != null) {
                                    ReMoDeLEntity ent = model.get(sel.entityId);
                                    if (ent != null) {
                                        ReMoDeLEntity copy = ent.copy();
                                        String fromEntityId = nr.anchorFromId != null ? findEntityIdByLocalId(nr.anchorFromId) : null;
                                        String toEntityId = nr.anchorToId != null ? findEntityIdByLocalId(nr.anchorToId) : null;
                                        copy.put("fromId", fromEntityId);
                                        copy.put("toId", toEntityId);
                                        copy.put("shapeType", nr.tool.name());
                                        copy.put("manualPosition", fromEntityId == null || toEntityId == null);
                                        copy.put("x1", (int) Math.round(nr.x1));
                                        copy.put("y1", (int) Math.round(nr.y1));
                                        copy.put("x2", (int) Math.round(nr.x2));
                                        copy.put("y2", (int) Math.round(nr.y2));
                                        if (nr.text != null) copy.put("text", nr.text);
                                        model.updateEntity(copy);
                                    }
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    redrawBuffer();
                                    repaint();
                                }
                            }
                        } else if (draggingMove) {
                            // translate shape by dx,dy
                            if (isConnectorTool(sel.tool)) {
                                Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                        sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, sel.text, sel.font,
                                        sel.entityId, sel.localId, null, null);
                                if (sel.entityId != null && model != null) {
                                    ReMoDeLEntity ent = model.get(sel.entityId);
                                    if (ent != null) {
                                        ReMoDeLEntity copy = ent.copy();
                                        copy.put("shapeType", nr.tool.name());
                                        copy.put("fromId", null);
                                        copy.put("toId", null);
                                        copy.put("manualPosition", true);
                                        copy.put("x1", (int) Math.round(nr.x1));
                                        copy.put("y1", (int) Math.round(nr.y1));
                                        copy.put("x2", (int) Math.round(nr.x2));
                                        copy.put("y2", (int) Math.round(nr.y2));
                                        if (nr.color != null) copy.put("colorRGB", nr.color.getRGB());
                                        copy.put("strokeWidth", nr.stroke);
                                        if (nr.text != null) copy.put("text", nr.text);
                                        model.updateEntity(copy);
                                    }
                                }
                                shapes.set(selectedIndex, nr);
                            } else if (sel.tool == Tool.TEXT) {
                                // preserve text and font when translating
                                double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                            } else {
                                Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                        sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                            }
                            redrawBuffer();
                            repaint();
                        } else if (resizing) {
                            // compute new bounds using logical coords, not rendered shape bounds.
                            // Actor geometry has fixed minimum limb extents, so rendered bounds can drift
                            // from the drag box and make resize feel speed-dependent.
                            double x1 = Math.min(sel.x1, sel.x2);
                            double y1 = Math.min(sel.y1, sel.y2);
                            double x2 = Math.max(sel.x1, sel.x2);
                            double y2 = Math.max(sel.y1, sel.y2);
                            switch (activeHandle) {
                                case 0: // top-left
                                    x1 = x; y1 = y;
                                    break;
                                case 1: // top-right
                                    x2 = x; y1 = y;
                                    break;
                                case 2: // bottom-right
                                    x2 = x; y2 = y;
                                    break;
                                case 3: // bottom-left
                                    x1 = x; y2 = y;
                                    break;
                                case 4: // top edge
                                    y1 = y;
                                    break;
                                case 5: // right edge
                                    x2 = x;
                                    break;
                                case 6: // bottom edge
                                    y2 = y;
                                    break;
                                case 7: // left edge
                                    x1 = x;
                                    break;
                                default:
                                    break;
                            }
                            if (sel.tool == Tool.TEXT) {
                                // resize text box, preserve text/font
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT,
                                        new Rectangle2D.Double(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1)),
                                        sel.color, sel.stroke, x1, y1, x2, y2, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                                redrawBuffer();
                                repaint();
                            } else {
                                ShapeRecord nr = createRecordFromTool(sel.tool, sel.color, sel.stroke, (int)x1, (int)y1, (int)x2, (int)y2);
                                if (nr != null) {
                                    String keptText = sel.text != null ? sel.text : nr.text;
                                    Font keptFont = normalizeTextFont(sel.font != null ? sel.font : nr.font);
                                    if (sel.entityId != null && model != null) {
                                        // preserve entity id
                                        ShapeRecord withId = new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke,
                                                nr.x1, nr.y1, nr.x2, nr.y2, keptText, keptFont, sel.entityId);
                                        model.updateEntity(entityFromShape(withId));
                                    } else {
                                        ShapeRecord withId = new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2,
                                                keptText, keptFont, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                        shapes.set(selectedIndex, withId);
                                        reanchorConnectorsFor(withId.localId);
                                    }
                                    redrawBuffer();
                                    repaint();
                                }
                            }
                        }
                    }
                    lastX = x; lastY = y;
                    return;
                }

                // other drawing tools: update preview
                preview = createPreview(pressX, pressY, x, y);
                lastX = x; lastY = y;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int x = toModelX(e.getX()), y = toModelY(e.getY());
                if (currentTool == Tool.FREEHAND) {
                    if (preview != null) {
                        if (model != null) {
                            ReMoDeLEntity ent = entityFromShape(preview);
                            model.addEntity(ent);
                            addUndoableEdit(new ShapeCreateEdit(null, ent.copy()));
                        } else {
                            shapes.add(preview);
                            addUndoableEdit(new ShapeCreateEdit(copyShapeRecord(preview), null));
                            // Auto-enter label editing for freehand shapes
                            int newIndex = shapes.size() - 1;
                            SwingUtilities.invokeLater(() -> startLabelEditingForDrawnShape(newIndex, Tool.TEXT));
                        }
                        Graphics2D g = getBufferGraphics();
                        drawRecord(g, preview, false);
                        g.dispose();
                        preview = null;
                        repaint();
                    }
                    lastX = lastY = -1;
                    statusConsumer.accept("Ready");
                    return;
                }

                if (currentTool == Tool.SELECT || currentTool == Tool.PAN) {
                    preview = null;
                    // finish move/resize
                    draggingMove = false;
                    resizing = false;
                    draggingConnectorEndpoint = false;
                    activeHandle = -1;
                    activeConnectorEndpoint = -1;
                    // finalize move/resize undo edit
                    if (currentMoveEdit != null) {
                        // capture 'after' state if shape still exists at that index
                        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
                            currentMoveEdit.setAfter(copyShapeRecord(shapes.get(selectedIndex)));
                        }
                        addUndoableEdit(currentMoveEdit);
                        currentMoveEdit = null;
                    }
                    redrawBuffer();
                    repaint();
                    statusConsumer.accept("Ready");
                    return;
                }

                if (preview != null && isConnectorTool(currentTool)) {
                    int fromIdx = findConnectorAnchorTarget(pressX, pressY, CONNECTOR_SNAP_MARGIN);
                    int toIdx = findConnectorAnchorTarget(x, y, CONNECTOR_SNAP_MARGIN);
                    boolean allowSelfLoop = isTransitionTool(currentTool);
                    ShapeRecord connector = buildConnectorRecord(currentTool, drawColor, strokeWidth, pressX, pressY, x, y);
                    boolean hasAttachment = fromIdx >= 0 || toIdx >= 0;
                    boolean bothAttached = fromIdx >= 0 && toIdx >= 0;
                    if (connector != null && hasAttachment) {
                        ShapeRecord fromShape = fromIdx >= 0 ? shapes.get(fromIdx) : null;
                        ShapeRecord toShape = toIdx >= 0 ? shapes.get(toIdx) : null;
                        boolean canSelfLoop = bothAttached && (fromIdx != toIdx || allowSelfLoop);
                        if (model != null && ((fromShape != null && fromShape.entityId != null) || (toShape != null && toShape.entityId != null))) {
                            ReMoDeLEntity ent = new ReMoDeLEntity();
                            ent.setType("connector");
                            if (fromShape.entityId != null) ent.put("fromId", fromShape.entityId);
                            if (toShape.entityId != null) ent.put("toId", toShape.entityId);
                            ent.put("shapeType", currentTool.name());
                            ent.put("colorRGB", drawColor.getRGB());
                            ent.put("strokeWidth", strokeWidth);
                            ent.put("text", getConnectorLabel(currentTool, null));
                            ent.put("manualPosition", !canSelfLoop);
                            ent.put("x1", (int) Math.round(connector.x1));
                            ent.put("y1", (int) Math.round(connector.y1));
                            ent.put("x2", (int) Math.round(connector.x2));
                            ent.put("y2", (int) Math.round(connector.y2));
                            model.addEntity(ent);
                            addUndoableEdit(new ShapeCreateEdit(null, ent.copy()));
                        } else {
                            ShapeRecord anchored = connector;
                            shapes.add(anchored);
                            addUndoableEdit(new ShapeCreateEdit(copyShapeRecord(anchored), null));
                            Graphics2D g = getBufferGraphics();
                            drawRecord(g, anchored, false);
                            g.dispose();
                        }

                        preview = null;
                        setCurrentTool(Tool.SELECT);
                        lastX = lastY = -1;
                        statusConsumer.accept("Ready");
                        repaint();
                        return;
                    }
                }

                if (preview != null) {
                    Tool drawnTool = preview.tool;
                    if (model != null) {
                        ReMoDeLEntity ent = entityFromShape(preview);
                        model.addEntity(ent);
                        addUndoableEdit(new ShapeCreateEdit(null, ent.copy()));
                    } else {
                        shapes.add(preview);
                        addUndoableEdit(new ShapeCreateEdit(copyShapeRecord(preview), null));
                        if (!isConnectorTool(preview.tool)) sortShapesByArea();
                        
                        // Auto-enter label editing for non-connector shapes
                        int newIndex = shapes.size() - 1;
                        if (newIndex >= 0 && !isConnectorTool(drawnTool)) {
                            // Schedule label editing to happen after repaint
                            SwingUtilities.invokeLater(() -> startLabelEditingForDrawnShape(newIndex, drawnTool));
                        }
                    }
                    Graphics2D g = getBufferGraphics();
                    drawRecord(g, preview, false);
                    g.dispose();
                    preview = null;
                    setCurrentTool(Tool.SELECT);
                }
                lastX = lastY = -1;
                statusConsumer.accept("Ready");
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) == 0) return;
            if (e.getWheelRotation() < 0) {
                zoomIn();
            } else if (e.getWheelRotation() > 0) {
                zoomOut();
            }
        });
        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                ensureBuffer();
                repaint();
            }
        });
    }

    /**
     * Routes to the appropriate label editing method based on the drawn shape type.
     * Called automatically after a shape is created.
     */
    private void startLabelEditingForDrawnShape(int index, Tool tool) {
        if (index < 0 || index >= shapes.size()) return;
        
        // Route to appropriate editing method
        if (tool == Tool.TEXT) {
            startEditingText(index);
        } else if (tool == Tool.RECTANGLE || tool == Tool.OVAL || 
                   tool == Tool.ROUNDED_RECTANGLE || tool == Tool.STATE ||
                   tool == Tool.ACTOR || tool == Tool.SYSTEM ||
                   tool == Tool.BOUNDARY) {
            startEditingShapeLabel(index);
        } else if (tool == Tool.OBJECT_TYPE) {
            startEditingObjectTypeLabel(index);
        } else if (tool == Tool.IMPACT || tool == Tool.ARROW_OPEN || tool == Tool.ARROW_FILLED 
                   || tool == Tool.ARROW_EMPTY || tool == Tool.ARROW_DIAMOND 
                   || tool == Tool.INITIAL_TRANSITION || tool == Tool.FINAL_TRANSITION) {
            startEditingImpactLabel(index);
        } else if (tool == Tool.REFERENCE) {
            startEditingReferenceLabel(index);
        }
    }

    private void startEditingShapeLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        
        // Only editable shapes
        if (sel.tool != Tool.RECTANGLE && sel.tool != Tool.OVAL && 
            sel.tool != Tool.ROUNDED_RECTANGLE && sel.tool != Tool.STATE &&
            sel.tool != Tool.ACTOR && sel.tool != Tool.SYSTEM &&
            sel.tool != Tool.BOUNDARY) {
            return;
        }
        
        Rectangle2D b = getShapeBounds(sel);
        if (b == null) return;
        beginLabelEdit(index);

        // Current text (default label based on shape type)
        String currentText = sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool);
        
        final JTextField tf = new JTextField(currentText);
        tf.setOpaque(false);
        tf.setBackground(new Color(0, 0, 0, 0));
        tf.setForeground(sel.color != null ? sel.color : drawColor);
        tf.setFont(zoomAwareEditorFont(sel.font));
        tf.setHorizontalAlignment(JTextField.CENTER);
        styleInlineEditor(tf);

        // Apply shape-specific border styling
        switch (sel.tool) {
            case STATE:
                // Pill shape border (fully rounded ends)
                int radius = (int) b.getHeight();
                tf.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(radius),
                    BorderFactory.createEmptyBorder(3, 10, 3, 10) // padding
                ));
                break;
            case OVAL:
                // Elliptical/oval border
                tf.setBorder(BorderFactory.createCompoundBorder(
                    new EllipseBorder(),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
                break;
            case ROUNDED_RECTANGLE:
                // Rounded rectangle border
                tf.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(15),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
                break;
            case BOUNDARY:
                // Keep boundary editing visually scoped to the tab area.
                tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(null, 0),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)
                ));
                break;
            case RECTANGLE:
            default:
                // Standard rectangular border
                tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(null, 0),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
                break;
        }

        // Position text field based on text width, centered on shape
        FontMetrics fm = tf.getFontMetrics(tf.getFont());
        int textWidth = fm.stringWidth(currentText);
        int textHeight = fm.getHeight();
        int editorWidth = Math.max(260, textWidth + 20);  
        int editorHeight = Math.max(20, textHeight + 6);
        
        if (sel.tool == Tool.BOUNDARY) {
            // Boundary labels live in the top tab, not in the boundary body.
            double tabHeight = boundaryTabHeight(b.getHeight());
            double tabWidth = boundaryTabWidth(b.getWidth());

            editorWidth = Math.max(40, Math.min((int) Math.round(tabWidth) - 6, Math.max(90, textWidth + 20)));
            editorHeight = Math.max(18, Math.min((int) Math.round(tabHeight) - 4, Math.max(20, textHeight + 6)));

            tf.setBounds(zoomedBounds(
                b.getX() + (tabWidth - editorWidth) / 2.0,
                b.getY() + (tabHeight - editorHeight) / 2.0,
                editorWidth,
                editorHeight
            ));
        } else if (sel.tool == Tool.ACTOR) {
            // Position actor label below the actor figure, centered
            tf.setBounds(zoomedBounds(
                b.getCenterX() - editorWidth / 2.0,
                b.getY() + b.getHeight() + 4,
                editorWidth,
                editorHeight
            ));
        } else {
            // Center horizontally and vertically within shape bounds
            tf.setBounds(zoomedBounds(
                b.getCenterX() - editorWidth / 2.0,
                b.getCenterY() - editorHeight / 2.0,
                editorWidth,
                editorHeight
            ));
        }
        
        this.add(tf);
        this.revalidate();
        this.repaint();
        tf.requestFocusInWindow();
        tf.selectAll();

        final boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            String newText = tf.getText().trim();
            if (newText.isEmpty()) newText = getDefaultLabelForTool(sel.tool);
            DrawingCanvas.this.remove(tf);
            endLabelEdit();
            updateShapeLabel(newText, index);
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        Runnable cancel = () -> {
            finished[0] = true;
            DrawingCanvas.this.remove(tf);
            endLabelEdit();
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        // Commit on Enter
        tf.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commit");
        tf.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { finish.run(); }
        });
        
        // Cancel on Escape
        tf.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        tf.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });

        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                finish.run();
            }
        });
    }

    private void startEditingObjectTypeLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.OBJECT_TYPE) return;
        Rectangle2D b = getShapeBounds(sel);
        if (b == null) return;
        beginLabelEdit(index);
        String newText = showObjectTypeEditorDialog(sel);
        endLabelEdit();
        if (newText != null) {
            updateShapeLabel(newText, index);
        }
        DrawingCanvas.this.revalidate();
        DrawingCanvas.this.repaint();
    }

    private void startEditingImpactLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.IMPACT && sel.tool != Tool.REFERENCE && sel.tool != Tool.ARROW_OPEN 
            && sel.tool != Tool.ARROW_FILLED && sel.tool != Tool.ARROW_EMPTY && sel.tool != Tool.ARROW_DIAMOND) return;
        beginLabelEdit(index);

        String currentText = sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool);
        if (isTransitionTool(sel.tool)) {
            currentText = formatTransitionLabel(currentText);
        }
        Font font = zoomAwareEditorFont(sel.font);

        final JTextField tf = new JTextField(currentText);
        tf.setOpaque(false);
        tf.setBackground(new Color(0, 0, 0, 0));
        tf.setForeground(sel.color != null ? sel.color : drawColor);
        tf.setFont(font);
        tf.setHorizontalAlignment(JTextField.CENTER);
        styleInlineEditor(tf);

        FontMetrics fm = getFontMetrics(font);
        int textWidth = fm.stringWidth(currentText);
        int textHeight = fm.getHeight();
        int editorWidth = Math.max(60, textWidth + 20);
        int editorHeight = Math.max(20, textHeight + 6);
        int midX = (int) Math.round((sel.x1 + sel.x2) / 2.0);
        int midY = (int) Math.round((sel.y1 + sel.y2) / 2.0);

        tf.setBounds(zoomedBounds(midX - editorWidth / 2.0, midY - editorHeight - 8, editorWidth, editorHeight));

        this.add(tf);
        this.revalidate();
        this.repaint();
        tf.requestFocusInWindow();
        tf.selectAll();

        final boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            String newText = tf.getText().trim();
            if (newText.isEmpty()) newText = getDefaultLabelForTool(sel.tool);
            DrawingCanvas.this.remove(tf);
            endLabelEdit();
            updateShapeLabel(newText, index);
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        Runnable cancel = () -> {
            finished[0] = true;
            DrawingCanvas.this.remove(tf);
            endLabelEdit();
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        tf.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commit");
        tf.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { finish.run(); }
        });

        tf.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        tf.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });

        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                finish.run();
            }
        });
    }

    private void startEditingReferenceLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.REFERENCE) return;
        beginLabelEdit(index);

        String currentText = sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool);
        Font font = zoomAwareEditorFont(sel.font);

        final JTextArea ta = new JTextArea(currentText);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0));
        ta.setForeground(sel.color != null ? sel.color : drawColor);
        ta.setFont(font);
        styleInlineEditor(ta);

        FontMetrics fm = getFontMetrics(font);
        int textWidth = fm.stringWidth(currentText);
        int textHeight = fm.getHeight();
        int editorWidth = Math.max(80, textWidth + 20);
        int editorHeight = Math.max(36, textHeight * 2 + 8);
        int midX = (int) Math.round((sel.x1 + sel.x2) / 2.0);
        int midY = (int) Math.round((sel.y1 + sel.y2) / 2.0);

        ta.setBounds(zoomedBounds(midX - editorWidth / 2.0, midY - editorHeight - 8, editorWidth, editorHeight));

        this.add(ta);
        this.revalidate();
        this.repaint();
        ta.requestFocusInWindow();
        ta.selectAll();

        final boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            String newText = ta.getText().trim();
            if (newText.isEmpty()) newText = getDefaultLabelForTool(sel.tool);
            DrawingCanvas.this.remove(ta);
            endLabelEdit();
            updateShapeLabel(newText, index);
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        Runnable cancel = () -> {
            finished[0] = true;
            DrawingCanvas.this.remove(ta);
            endLabelEdit();
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        ta.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "commit");
        ta.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { finish.run(); }
        });

        ta.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        ta.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });

        ta.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                finish.run();
            }
        });
    }

    /**
     * Custom border that draws a rounded rectangle outline matching STATE shapes.
     */
    class RoundedBorder implements javax.swing.border.Border {
        private final int radius;
        
        public RoundedBorder(int radius) {
            this.radius = radius;
        }
        
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0)); 
            // g2.setStroke(new BasicStroke(2));
            // g2.drawRoundRect(x, y, width + 1, height, radius, radius);
            g2.dispose();
        }
        
        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }
        
        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /**
     * Custom border that draws an elliptical outline matching OVAL/Task shapes.
     */
    class EllipseBorder implements javax.swing.border.Border {
        
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(100, 150, 255)); // Blue outline
            // g2.setStroke(new BasicStroke(2));
            // g2.drawOval(x, y, width - 1, height - 1);
            g2.dispose();
        }
        
        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }
        
        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /**
     * Get the default label text for a given tool type.
     */
    private String getDefaultLabelForTool(Tool tool) {
        switch (tool) {
            case RECTANGLE: return "Object";
            case OBJECT_TYPE: return "Object\nattribute";
            case ACTOR: return "Actor";
            case SYSTEM: return "System";
            case OVAL: return "Task";
            case ROUNDED_RECTANGLE: return "Process";
            case STATE: return "State";
            case LINE: return "Association";
            case AUTHORISATION: return "Authorisation";
            case ARROW_FILLED: return "datum";
            case ARROW_OPEN: return "event";
            case INITIAL_TRANSITION: return "enter";
            case FINAL_TRANSITION: return "exit";
            case IMPACT: return "create";
            case REFERENCE: {
                if (referenceQualifier == null || referenceQualifier.isBlank()) return referenceDefaultName;
                return referenceDefaultName + "\n{" + referenceQualifier + "}";
            }
            default: return "";
        }
    }

    private static class TransitionLabelParts {
        final String event;
        final String guard;
        final String action;

        TransitionLabelParts(String event, String guard, String action) {
            this.event = event;
            this.guard = guard;
            this.action = action;
        }
    }

    private TransitionLabelParts parseTransitionLabel(String text) {
        if (text == null) return new TransitionLabelParts("", "", "");

        String normalized = text.trim().replaceAll("\\R+", " ").replaceAll("\\s+", " ");
        if (normalized.isBlank()) return new TransitionLabelParts("", "", "");

        String guard = "";
        String action = "";
        String event = normalized;

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

    private String formatTransitionLabel(String text) {
        TransitionLabelParts parts = parseTransitionLabel(text);
        StringBuilder out = new StringBuilder();
        if (parts.event != null && !parts.event.isBlank()) {
            out.append(parts.event.trim());
        }
        if (parts.guard != null && !parts.guard.isBlank()) {
            if (out.length() > 0) out.append(' ');
            out.append('[').append(parts.guard.trim()).append(']');
        }
        if (parts.action != null && !parts.action.isBlank()) {
            if (out.length() > 0) out.append(" / ");
            out.append(parts.action.trim());
        }
        return out.toString();
    }

    private ShapeRecord growShapeForLabel(ShapeRecord sel, String text) {
        if (sel == null) return null;
        if (text == null) text = "";

        if (isTransitionTool(sel.tool)) {
            text = formatTransitionLabel(text);
        }

        Font font = normalizeTextFont(sel.font);
        // Connectors are lines — changing geometry based on text width breaks their endpoints
        if (isConnectorTool(sel.tool)) {
            return new ShapeRecord(sel.tool, sel.shape, sel.color, sel.stroke,
                sel.x1, sel.y1, sel.x2, sel.y2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        }

        if (sel.tool == Tool.OBJECT_TYPE) {
            int[] size = measureObjectTypeSize(text, font);
            Rectangle2D b = getShapeBounds(sel);
            if (b == null) return sel;

            double cx = b.getCenterX();
            double cy = b.getCenterY();
            int nx1 = (int) Math.round(cx - size[0] / 2.0);
            int ny1 = (int) Math.round(cy - size[1] / 2.0);
            int nx2 = nx1 + size[0];
            int ny2 = ny1 + size[1];
            Shape shape = new Rectangle2D.Double(nx1, ny1, size[0], size[1]);
            return new ShapeRecord(sel.tool, shape, sel.color, sel.stroke,
                nx1, ny1, nx2, ny2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        }

        FontMetrics fm = getFontMetrics(font);
        Rectangle2D b = getShapeBounds(sel);
        if (b == null) return sel;

        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) lines = new String[] { "" };

        int maxLineW = 0;
        for (String line : lines) {
            maxLineW = Math.max(maxLineW, fm.stringWidth(line != null ? line : ""));
        }

        double requiredW = b.getWidth();
        double requiredH = b.getHeight();

        if (isConstrainedSizeTool(sel.tool)) {
            // Keep fixed-size entities constant while still updating their label text.
            return new ShapeRecord(sel.tool, sel.shape, sel.color, sel.stroke,
                sel.x1, sel.y1, sel.x2, sel.y2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        } else {
            switch (sel.tool) {
                case ACTOR:
                    // Actor label is drawn outside the shape; only grow width to keep names readable.
                    requiredW = Math.max(requiredW, maxLineW + 20);
                    break;
                default:
                    requiredW = Math.max(requiredW, maxLineW + 20);
                    requiredH = Math.max(requiredH, Math.max(24, lines.length * fm.getHeight() + 12));
                    break;
            }
        }

        if (requiredW <= b.getWidth() && requiredH <= b.getHeight()) {
            return new ShapeRecord(sel.tool, sel.shape, sel.color, sel.stroke,
                sel.x1, sel.y1, sel.x2, sel.y2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        }

        double cx = b.getCenterX();
        double cy = b.getCenterY();
        int nx1 = (int) Math.round(cx - requiredW / 2.0);
        int ny1 = (int) Math.round(cy - requiredH / 2.0);
        int nx2 = (int) Math.round(cx + requiredW / 2.0);
        int ny2 = (int) Math.round(cy + requiredH / 2.0);

        ShapeRecord grown = createRecordFromTool(sel.tool, sel.color, sel.stroke, nx1, ny1, nx2, ny2);
        if (grown == null) {
            return new ShapeRecord(sel.tool, sel.shape, sel.color, sel.stroke,
                sel.x1, sel.y1, sel.x2, sel.y2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        }

        return new ShapeRecord(grown.tool, grown.shape, grown.color, grown.stroke,
            grown.x1, grown.y1, grown.x2, grown.y2,
            text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
    }

    /**
     * Returns a label that is unique among all non-connector shapes, excluding the
     * shape at {@code ownIndex}. If {@code desired} is already taken, appends
     * " 2", " 3", etc. until a free name is found.
     */
    private String uniquifyLabel(String desired, int ownIndex) {
        if (desired == null) return desired;
        String candidate = desired;
        int suffix = 2;
        outer:
        while (true) {
            for (int i = 0; i < shapes.size(); i++) {
                if (i == ownIndex) continue;
                ShapeRecord r = shapes.get(i);
                if (!isConnectorTool(r.tool) && candidate.equalsIgnoreCase(r.text)) {
                    candidate = desired + " " + suffix++;
                    continue outer;
                }
            }
            return candidate;
        }
    }

    private String uniquifyObjectTypeLabel(String desired, int ownIndex) {
        if (desired == null) return desired;
        ObjectTypeEditParts parts = parseObjectTypeParts(desired);
        String name = parts.name != null ? parts.name : "Object";
        String uniqueName = uniquifyLabel(name, ownIndex);
        return buildObjectTypeLabel(uniqueName, parts.attributes);
    }

    
    private void updateShapeLabel(String newText, int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        // Enforce unique labels for non-connector shapes so the label can serve as
        // a stable identifier in exports. If the requested text already belongs to
        // another shape, append " 2", " 3", etc. until the name is unique.
        if (!isConnectorTool(sel.tool)) {
            newText = sel.tool == Tool.OBJECT_TYPE
                ? uniquifyObjectTypeLabel(newText, index)
                : uniquifyLabel(newText, index);
        }
        ShapeRecord nr = growShapeForLabel(sel, newText);
        if (nr == null) return;
        
        // Register undo
        ShapeRecord before = copyShapeRecord(sel);
        ShapeRecord after = copyShapeRecord(nr);

        // Update local state immediately so subsequent drag operations don't overwrite
        // the edited text with stale/default labels while model listeners are pending.
        shapes.set(index, nr);
        if (!isConnectorTool(nr.tool)) {
            reanchorConnectorsFor(nr.localId);
        } else {
            redrawBuffer();
            repaint();
        }
        
        // Update shape (via model if backed by one)
        if (sel.entityId != null && model != null) {
            if (isConnectorTool(sel.tool)) {
                ReMoDeLEntity ent = model.get(sel.entityId);
                if (ent != null) {
                    ReMoDeLEntity copy = ent.copy();
                    copy.put("text", newText);
                    model.updateEntity(copy);
                }
            } else {
                model.updateEntity(entityFromShape(after));
            }
        }
        
        addUndoableEdit(new TextEdit(index, before, after));
    }

    /**
     * Reorder shapes so larger non-connector shapes are drawn first (behind)
     * and connectors are always last (on top). Hit-testing iterates in reverse,
     * so smaller shapes are hit-tested before the large shapes that contain them.
     */
    private void sortShapesByArea() {
        java.util.List<ShapeRecord> nonConn = new java.util.ArrayList<>();
        java.util.List<ShapeRecord> conn = new java.util.ArrayList<>();
        for (ShapeRecord r : shapes) {
            if (isConnectorTool(r.tool)) conn.add(r);
            else nonConn.add(r);
        }
        // Larger shapes first (drawn behind); connectors always on top
        nonConn.sort((a, b) -> Double.compare(shapeArea(b), shapeArea(a)));
        shapes.clear();
        shapes.addAll(nonConn);
        shapes.addAll(conn);
    }

    private double shapeArea(ShapeRecord r) {
        if (r == null || r.shape == null) return 0;
        java.awt.geom.Rectangle2D b = r.shape.getBounds2D();
        return b.getWidth() * b.getHeight();
    }

    // Hit-testing iterates in reverse; smaller shapes tested before
    // large containing shapes
    private int hitTest(int x, int y) {

        Point2D p = new Point2D.Double(x, y);
        for (int i = shapes.size() - 1; i >= 0; i--) {
            ShapeRecord r = shapes.get(i);
            Shape pick = new BasicStroke(Math.max(6f, r.stroke + 6f)).createStrokedShape(r.shape);
            if (pick.contains(p)) return i;
            if (r.tool == Tool.ACTOR) {
                Rectangle2D actorBounds = actorAnchorBounds(r);
                if (actorBounds != null && actorBounds.contains(p)) return i;
            }
            // for filled shapes also test interior
            try {
                if (r.shape.contains(p)) return i;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private Rectangle2D getShapeBounds(ShapeRecord r) {

        return r.shape.getBounds2D();
    }

    private int handleHit(Rectangle2D b, int x, int y) {

        if (b == null) return -1;
        double hx = b.getX(), hy = b.getY(), hw = b.getWidth(), hh = b.getHeight();

        double mx = hx + hw / 2.0;
        double my = hy + hh / 2.0;
        double edgeHandle = Math.max(8, HANDLE_SIZE - 2);

        Rectangle2D[] handles = new Rectangle2D[] {
                new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tl
                new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tr
                new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // br
            new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // bl
            new Rectangle2D.Double(mx - edgeHandle/2, hy - edgeHandle/2, edgeHandle, edgeHandle), // top
            new Rectangle2D.Double(hx + hw - edgeHandle/2, my - edgeHandle/2, edgeHandle, edgeHandle), // right
            new Rectangle2D.Double(mx - edgeHandle/2, hy + hh - edgeHandle/2, edgeHandle, edgeHandle), // bottom
            new Rectangle2D.Double(hx - edgeHandle/2, my - edgeHandle/2, edgeHandle, edgeHandle) // left
        };
        for (int i = 0; i < handles.length; i++) {
            if (handles[i].contains(x, y)) return i;
            Rectangle2D expanded = new Rectangle2D.Double(
                handles[i].getX() - HANDLE_HIT_MARGIN,
                handles[i].getY() - HANDLE_HIT_MARGIN,
                handles[i].getWidth() + HANDLE_HIT_MARGIN * 2.0,
                handles[i].getHeight() + HANDLE_HIT_MARGIN * 2.0
            );
            if (expanded.contains(x, y)) return i;
        }
        return -1;
    }

    private int connectorHandleHit(ShapeRecord r, int x, int y) {
        if (r == null) return -1;
        double h = HANDLE_SIZE + HANDLE_HIT_MARGIN;
        Rectangle2D start = new Rectangle2D.Double(r.x1 - h / 2.0, r.y1 - h / 2.0, h, h);
        Rectangle2D end = new Rectangle2D.Double(r.x2 - h / 2.0, r.y2 - h / 2.0, h, h);
        if (start.contains(x, y)) return 0;
        if (end.contains(x, y)) return 1;
        return -1;
    }

    private ShapeRecord snapConnectorToTouchedShapes(ShapeRecord sel) {
        if (sel == null || !isConnectorTool(sel.tool)) return sel;

        ShapeRecord fromShape = null;
        ShapeRecord toShape = null;

        int fromIdx = findConnectorAnchorTarget((int) Math.round(sel.x1), (int) Math.round(sel.y1), HANDLE_HIT_MARGIN);
        if (fromIdx >= 0 && fromIdx < shapes.size()) {
            fromShape = shapes.get(fromIdx);
        }

        int toIdx = findConnectorAnchorTarget((int) Math.round(sel.x2), (int) Math.round(sel.y2), HANDLE_HIT_MARGIN);
        if (toIdx >= 0 && toIdx < shapes.size()) {
            toShape = shapes.get(toIdx);
        }

        String fromAnchor = fromShape != null ? fromShape.localId : null;
        String toAnchor = toShape != null ? toShape.localId : null;

        double nx1 = sel.x1;
        double ny1 = sel.y1;
        double nx2 = sel.x2;
        double ny2 = sel.y2;

        Shape connectorShape = new Line2D.Double(nx1, ny1, nx2, ny2);

        if (fromShape != null && toShape != null) {
            if (isSelfLoopTransition(sel.tool, fromShape, toShape)) {
                Point2D.Double[] anchors = computeSelfLoopAnchors(fromShape);
                nx1 = anchors[0].x;
                ny1 = anchors[0].y;
                nx2 = anchors[1].x;
                ny2 = anchors[1].y;
                connectorShape = buildSelfLoopArc(fromShape, anchors[0], anchors[1]);
            } else if (fromShape != toShape) {
                Point2D.Double[] anchors = computeConnectorAnchors(fromShape, toShape);
                nx1 = anchors[0].x;
                ny1 = anchors[0].y;
                nx2 = anchors[1].x;
                ny2 = anchors[1].y;
                connectorShape = new Line2D.Double(nx1, ny1, nx2, ny2);
            }
        } else {
            if (fromShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(fromShape, getShapeCenter(fromShape), new Point2D.Double(sel.x2, sel.y2));
                if (snapped != null) {
                    nx1 = snapped.x;
                    ny1 = snapped.y;
                }
            }
            if (toShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(toShape, getShapeCenter(toShape), new Point2D.Double(sel.x1, sel.y1));
                if (snapped != null) {
                    nx2 = snapped.x;
                    ny2 = snapped.y;
                }
            }
            connectorShape = new Line2D.Double(nx1, ny1, nx2, ny2);
        }

        return new ShapeRecord(sel.tool, connectorShape, sel.color, sel.stroke, nx1, ny1, nx2, ny2,
            sel.text, sel.font, sel.entityId, sel.localId, fromAnchor, toAnchor);
    }

    private void persistConnectorRecord(int index, ShapeRecord nr) {
        if (nr == null) return;

        if (nr.entityId != null && model != null) {
            ReMoDeLEntity ent = model.get(nr.entityId);
            if (ent != null) {
                ReMoDeLEntity copy = ent.copy();
                String fromEntityId = nr.anchorFromId != null ? findEntityIdByLocalId(nr.anchorFromId) : null;
                String toEntityId = nr.anchorToId != null ? findEntityIdByLocalId(nr.anchorToId) : null;
                copy.put("fromId", fromEntityId);
                copy.put("toId", toEntityId);
                copy.put("shapeType", nr.tool.name());
                copy.put("manualPosition", fromEntityId == null || toEntityId == null);
                copy.put("x1", (int) Math.round(nr.x1));
                copy.put("y1", (int) Math.round(nr.y1));
                copy.put("x2", (int) Math.round(nr.x2));
                copy.put("y2", (int) Math.round(nr.y2));
                if (nr.color != null) copy.put("colorRGB", nr.color.getRGB());
                copy.put("strokeWidth", nr.stroke);
                if (nr.text != null) copy.put("text", nr.text);
                model.updateEntity(copy);
            }
        }

        if (index >= 0 && index < shapes.size()) {
            shapes.set(index, nr);
        }
    }

    private ShapeRecord updateConnectorEndpointRecord(ShapeRecord sel, int endpoint, int x, int y) {
        if (sel == null) return null;

        String fromAnchor = sel.anchorFromId;
        String toAnchor = sel.anchorToId;
        double nx1 = sel.x1, ny1 = sel.y1, nx2 = sel.x2, ny2 = sel.y2;

        int hitIdx = findConnectorAnchorTarget(x, y, CONNECTOR_SNAP_MARGIN);
        ShapeRecord hitShape = (hitIdx >= 0 && hitIdx < shapes.size()) ? shapes.get(hitIdx) : null;

        if (endpoint == 0) {
            fromAnchor = hitShape != null ? hitShape.localId : null;
            if (hitShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(hitShape, getShapeCenter(hitShape), new Point2D.Double(nx2, ny2));
                if (snapped != null) {
                    nx1 = snapped.x;
                    ny1 = snapped.y;
                } else {
                    nx1 = x;
                    ny1 = y;
                }
            } else {
                nx1 = x;
                ny1 = y;
            }
        } else {
            toAnchor = hitShape != null ? hitShape.localId : null;
            if (hitShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(hitShape, getShapeCenter(hitShape), new Point2D.Double(nx1, ny1));
                if (snapped != null) {
                    nx2 = snapped.x;
                    ny2 = snapped.y;
                } else {
                    nx2 = x;
                    ny2 = y;
                }
            } else {
                nx2 = x;
                ny2 = y;
            }
        }

        ShapeRecord fromShape = findShapeByLocalId(fromAnchor);
        ShapeRecord toShape = findShapeByLocalId(toAnchor);
        Shape connectorShape = new Line2D.Double(nx1, ny1, nx2, ny2);
        if (fromShape != null && toShape != null) {
            if (isSelfLoopTransition(sel.tool, fromShape, toShape)) {
                Point2D.Double[] anchors = computeSelfLoopAnchors(fromShape);
                nx1 = anchors[0].x;
                ny1 = anchors[0].y;
                nx2 = anchors[1].x;
                ny2 = anchors[1].y;
                connectorShape = buildSelfLoopArc(fromShape, anchors[0], anchors[1]);
            } else if (fromShape != toShape) {
                Point2D.Double[] anchors = computeConnectorAnchors(fromShape, toShape);
                nx1 = anchors[0].x;
                ny1 = anchors[0].y;
                nx2 = anchors[1].x;
                ny2 = anchors[1].y;
                connectorShape = new Line2D.Double(nx1, ny1, nx2, ny2);
            }
        }

        ShapeRecord updated = new ShapeRecord(sel.tool, connectorShape, sel.color, sel.stroke, nx1, ny1, nx2, ny2,
            sel.text, sel.font, sel.entityId, sel.localId, fromAnchor, toAnchor);
        if (updated.entityId == null && updated.anchorFromId == null && updated.anchorToId == null) {
            // no model-backed anchors: keep explicit coordinates for free connector editing
            return new ShapeRecord(updated.tool, updated.shape, updated.color, updated.stroke,
                updated.x1, updated.y1, updated.x2, updated.y2,
                updated.text, updated.font, updated.entityId, updated.localId, null, null);
        }
        return updated;
    }

    private ShapeRecord buildConnectorRecord(Tool tool, Color color, float sWidth, int x1, int y1, int x2, int y2) {
        if (!isConnectorTool(tool)) return null;

        int fromIdx = findConnectorAnchorTarget(x1, y1, CONNECTOR_SNAP_MARGIN);
        int toIdx = findConnectorAnchorTarget(x2, y2, CONNECTOR_SNAP_MARGIN);
        ShapeRecord fromShape = fromIdx >= 0 && fromIdx < shapes.size() ? shapes.get(fromIdx) : null;
        ShapeRecord toShape = toIdx >= 0 && toIdx < shapes.size() ? shapes.get(toIdx) : null;

        Point2D.Double start = new Point2D.Double(x1, y1);
        Point2D.Double end = new Point2D.Double(x2, y2);
        String fromAnchor = fromShape != null ? fromShape.localId : null;
        String toAnchor = toShape != null ? toShape.localId : null;
        Shape connectorShape = new Line2D.Double(start.x, start.y, end.x, end.y);

        if (fromShape != null && toShape != null) {
            if (isSelfLoopTransition(tool, fromShape, toShape)) {
                Point2D.Double[] anchors = computeSelfLoopAnchors(fromShape);
                start = anchors[0];
                end = anchors[1];
                connectorShape = buildSelfLoopArc(fromShape, start, end);
            } else if (fromShape != toShape) {
                Point2D.Double[] anchors = computeConnectorAnchors(fromShape, toShape);
                start = anchors[0];
                end = anchors[1];
                connectorShape = new Line2D.Double(start.x, start.y, end.x, end.y);
            }
        } else {
            if (fromShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(fromShape, getShapeCenter(fromShape), new Point2D.Double(x2, y2));
                if (snapped != null) start = snapped;
            }
            if (toShape != null) {
                Point2D.Double snapped = intersectShapeBoundary(toShape, getShapeCenter(toShape), new Point2D.Double(x1, y1));
                if (snapped != null) end = snapped;
            }
            connectorShape = new Line2D.Double(start.x, start.y, end.x, end.y);
        }

        return new ShapeRecord(tool, connectorShape, color, sWidth, start.x, start.y, end.x, end.y,
            getConnectorLabel(tool, null), defaultTextFont(), null, null, fromAnchor, toAnchor);
    }

    private ShapeRecord createPreview(int x1, int y1, int x2, int y2) {
        
        Shape s = null;
        Tool t = currentTool;
        if (isConnectorTool(t)) {
            return buildConnectorRecord(t, drawColor, strokeWidth, x1, y1, x2, y2);
        }
        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1);
        int rh = Math.abs(y2 - y1);
        String text = null;
        Font font = defaultTextFont();

        if (isConstrainedSizeTool(t)) {
            // Creation preview for constrained entities is always the default size.
            rw = ENTITY_DEFAULT_WIDTH;
            rh = ENTITY_DEFAULT_HEIGHT;
            rx = x1;
            ry = y1;
            x2 = x1 + rw;
            y2 = y1 + rh;
        }

        if (t == Tool.BOUNDARY && rw <= BOUNDARY_CLICK_THRESHOLD && rh <= BOUNDARY_CLICK_THRESHOLD) {
            int canvasW = Math.max(getWidth(), getPreferredSize().width);
            int canvasH = Math.max(getHeight(), getPreferredSize().height);
            rw = Math.max(120, (int) Math.round(canvasW * 0.75));
            rh = Math.max(120, (int) Math.round(canvasH * 0.50));
            rx = Math.max(BOUNDARY_MARGIN, (canvasW - rw) / 2);
            ry = BOUNDARY_MARGIN;
            x1 = rx;
            y1 = ry;
            x2 = rx + rw;
            y2 = ry + rh;
        }

        switch (t) {
            case LINE:
            case AUTHORISATION:
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_OPEN:
            case ARROW_EMPTY:
            case IMPACT:
            case REFERENCE:
            case ENACTS:
            case INITIAL_TRANSITION:
            case FINAL_TRANSITION:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case STATE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, rh, rh);
                text = "State";
                break;
            case OVAL:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                text = "Task";
                break;
            case RECTANGLE:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                text = "Object";
                break;
            case OBJECT_TYPE:
                text = buildObjectTypeTemplate(objectTypeAttributeCount);
                int[] objectTypeSize = measureObjectTypeSize(text, font);
                rw = objectTypeSize[0];
                rh = objectTypeSize[1];
                rx = x1;
                ry = y1;
                x2 = rx + rw;
                y2 = ry + rh;
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                break;
            case SYSTEM:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                text = "System";
                break;
            case ROUNDED_RECTANGLE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                text = "Process";
                break;
            case BOUNDARY:
                s = buildBoundaryShape(rx, ry, rw, rh);
                text = "Boundary";
                break;
            case ACTOR:
                int actorPreviewW = ACTOR_DEFAULT_WIDTH;
                int actorPreviewH = ACTOR_DEFAULT_HEIGHT;
                s = buildActorShape(x1, y1, actorPreviewW, actorPreviewH);
                x2 = x1 + actorPreviewW;
                y2 = y1 + actorPreviewH;
                text = "Actor";
                break;
            default:
                s = new Line2D.Double(x1, y1, x2, y2);
        }
        return new ShapeRecord(t, s, drawColor, strokeWidth, x1, y1, x2, y2, text, font);
    }

    private ShapeRecord createRecordFromTool(Tool t, Color c, float sWidth, int x1, int y1, int x2, int y2) {

        // helper to construct a new shape record with normalized coords
        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1);
        int rh = Math.abs(y2 - y1);

        if (t == Tool.BOUNDARY && rw <= BOUNDARY_CLICK_THRESHOLD && rh <= BOUNDARY_CLICK_THRESHOLD) {
            int canvasW = Math.max(getWidth(), getPreferredSize().width);
            int canvasH = Math.max(getHeight(), getPreferredSize().height);
            rw = Math.max(240, canvasW);
            rh = Math.max(BOUNDARY_MIN_HEIGHT, canvasH - (BOUNDARY_MARGIN * 2));
            rx = Math.max(BOUNDARY_MARGIN, (canvasW - rw) / 2);
            ry = BOUNDARY_MARGIN;
            x1 = rx;
            y1 = ry;
            x2 = rx + rw;
            y2 = ry + rh;
        }

        int[] fixed = enforceDefaultSize(t, rw, rh);
        if (isConstrainedSizeTool(t)) {
            rw = fixed[0];
            rh = fixed[1];
            rx = Math.min(x1, x2);
            ry = Math.min(y1, y2);
            x1 = rx;
            y1 = ry;
            x2 = rx + rw;
            y2 = ry + rh;
        }
        Shape s = null;
        String text = null;
        Font font = defaultTextFont();
        
        switch (t) {
            case LINE:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Association";
                break;
            case AUTHORISATION:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Authorisation";
                break;
            case ENACTS:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Enacts";
                break;
            case ARROW_FILLED:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Data Flow";
                break;
            case ARROW_DIAMOND:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case ARROW_OPEN:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Transition";
                break;
            case INITIAL_TRANSITION:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Initial";
                break;
            case FINAL_TRANSITION:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "Final";
                break;
            case REFERENCE:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = getDefaultLabelForTool(Tool.REFERENCE);
                break;
            case IMPACT:
                s = new Line2D.Double(x1, y1, x2, y2);
                text = "create";
                break;
            case ARROW_EMPTY:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case STATE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, rh, rh);
                text = "State";
                break;
            case OVAL:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                text = "Task";
                break;
            case RECTANGLE:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                text = "Object";
                break;
            case OBJECT_TYPE:
                text = buildObjectTypeTemplate(objectTypeAttributeCount);
                int[] objectTypeSize = measureObjectTypeSize(text, font);
                rw = objectTypeSize[0];
                rh = objectTypeSize[1];
                rx = x1;
                ry = y1;
                x2 = rx + rw;
                y2 = ry + rh;
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                break;
            case SYSTEM:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                text = "System";
                break;
            case ROUNDED_RECTANGLE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                text = "Process";
                break;
            case BOUNDARY:
                s = buildBoundaryShape(rx, ry, rw, rh);
                text = "Boundary";
                break;
            case ACTOR:
                int actorX = Math.min(x1, x2);
                int actorY = Math.min(y1, y2);
                int actorW = ACTOR_DEFAULT_WIDTH;
                int actorH = ACTOR_DEFAULT_HEIGHT;
                s = buildActorShape(actorX, actorY, actorW, actorH);
                x1 = actorX;
                y1 = actorY;
                x2 = actorX + actorW;
                y2 = actorY + actorH;
                text = "Actor";
                break;
            case FREEHAND:
            default:
                // fallback to a tiny line
                s = new Line2D.Double(x1, y1, x2, y2);
        }
        return new ShapeRecord(t, s, c, sWidth, x1, y1, x2, y2, text, font);
    }

    private Shape buildActorShape(double x, double y, double w, double h) {
        double width = Math.max(10, w);
        double height = Math.max(16, h);
        double headRadius = Math.max(4, Math.min(width, height) * 0.18);
        double headCx = x + width / 2.0;
        double headCy = y + headRadius + 1;

        double bodyTopY = headCy + headRadius + 1;
        double bodyBottomY = y + height * 0.68;
        double armY = bodyTopY + (bodyBottomY - bodyTopY) * 0.35;
        double legY = y + height - 2;

        double armHalf = Math.max(6, width * 0.28);
        double legHalf = Math.max(6, width * 0.24);

        Path2D p = new Path2D.Double();
        Ellipse2D head = new Ellipse2D.Double(headCx - headRadius, headCy - headRadius, headRadius * 2, headRadius * 2);
        p.append(head, false);
        p.moveTo(headCx, bodyTopY);
        p.lineTo(headCx, bodyBottomY);
        p.moveTo(headCx - armHalf, armY);
        p.lineTo(headCx + armHalf, armY);
        p.moveTo(headCx, bodyBottomY);
        p.lineTo(headCx - legHalf, legY);
        p.moveTo(headCx, bodyBottomY);
        p.lineTo(headCx + legHalf, legY);
        return p;
    }

    private Graphics2D getBufferGraphics() {
        ensureBuffer();
        Graphics2D g = buf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    private Point2D.Double computeArrowBase(double x1, double y1, double x2, double y2, float stroke) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return new Point2D.Double(x2, y2);
        double ux = dx / len, uy = dy / len;
        double headLen = Math.max(8, 6 + stroke * 2); 
        double bx = x2 - ux * headLen;
        double by = y2 - uy * headLen;
        return new Point2D.Double(bx, by);
    }

    private static class ConnectorCrossing {
        final ShapeRecord top;
        final Point2D.Double point;

        ConnectorCrossing(ShapeRecord top, Point2D.Double point) {
            this.top = top;
            this.point = point;
        }
    }

    private java.util.List<ConnectorCrossing> computeConnectorCrossings() {
        java.util.List<Integer> connectorIdx = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            ShapeRecord r = shapes.get(i);
            if (r != null && isConnectorTool(r.tool)) connectorIdx.add(i);
        }

        java.util.List<ConnectorCrossing> crossings = new ArrayList<>();
        for (int a = 0; a < connectorIdx.size(); a++) {
            int ia = connectorIdx.get(a);
            ShapeRecord ra = shapes.get(ia);
            for (int b = a + 1; b < connectorIdx.size(); b++) {
                int ib = connectorIdx.get(b);
                ShapeRecord rb = shapes.get(ib);

                Point2D.Double p = segmentIntersectionPoint(ra.x1, ra.y1, ra.x2, ra.y2, rb.x1, rb.y1, rb.x2, rb.y2);
                if (p == null) continue;
                if (isNearConnectorEndpoint(ra, p, CROSSING_ENDPOINT_PADDING)
                    || isNearConnectorEndpoint(rb, p, CROSSING_ENDPOINT_PADDING)) {
                    continue;
                }

                // Later-drawn connector gets the bridge marker so overlaps are deterministic.
                ShapeRecord top = ia > ib ? ra : rb;
                crossings.add(new ConnectorCrossing(top, p));
            }
        }
        return crossings;
    }

    private boolean isNearConnectorEndpoint(ShapeRecord r, Point2D p, double threshold) {
        if (r == null || p == null) return false;
        double t2 = threshold * threshold;
        double d1 = p.distanceSq(r.x1, r.y1);
        double d2 = p.distanceSq(r.x2, r.y2);
        return d1 < t2 || d2 < t2;
    }

    private Point2D.Double segmentIntersectionPoint(
        double x1, double y1, double x2, double y2,
        double x3, double y3, double x4, double y4
    ) {
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(den) < 1e-9) return null;

        double px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den;
        double py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den;

        if (!pointOnSegment(px, py, x1, y1, x2, y2) || !pointOnSegment(px, py, x3, y3, x4, y4)) {
            return null;
        }
        return new Point2D.Double(px, py);
    }

    private boolean pointOnSegment(double px, double py, double ax, double ay, double bx, double by) {
        double minX = Math.min(ax, bx) - 1e-6;
        double maxX = Math.max(ax, bx) + 1e-6;
        double minY = Math.min(ay, by) - 1e-6;
        double maxY = Math.max(ay, by) + 1e-6;
        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }

    private void drawConnectorBridge(Graphics2D g, ShapeRecord connector, Point2D p) {
        if (connector == null || p == null) return;

        double angle = Math.atan2(connector.y2 - connector.y1, connector.x2 - connector.x1);
        double radius = Math.max(CROSSING_BRIDGE_RADIUS, connector.stroke * 2.4);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(p.getX(), p.getY());
        g2.rotate(angle);

        // Cut a small gap in the top connector, then draw a smooth bridge arc.
        g2.setColor(getBackground());
        g2.setStroke(new BasicStroke(connector.stroke + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(-radius, 0, radius, 0));

        g2.setColor(connector.color != null ? connector.color : drawColor);
        g2.setStroke(new BasicStroke(connector.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Arc2D bridge = new Arc2D.Double(-radius, -radius, radius * 2, radius * 2, 180, 180, Arc2D.OPEN);
        g2.draw(bridge);
        g2.dispose();
    }

    private Point2D.Double arcTangentTail(Arc2D arc) {
        if (arc == null) return new Point2D.Double(0, 0);

        double endAngle = arc.getAngleStart() + arc.getAngleExtent();
        double direction = arc.getAngleExtent() >= 0 ? 1.0 : -1.0;
        double delta = Math.min(8.0, Math.abs(arc.getAngleExtent()) / 4.0);
        if (delta < 0.5) delta = 0.5;
        double tailAngle = endAngle - direction * delta;

        double rx = arc.getWidth() / 2.0;
        double ry = arc.getHeight() / 2.0;
        double cx = arc.getX() + rx;
        double cy = arc.getY() + ry;
        double rad = Math.toRadians(tailAngle);

        return new Point2D.Double(cx + rx * Math.cos(rad), cy - ry * Math.sin(rad));
    }

    private void drawRecord(Graphics2D g, ShapeRecord r, boolean isPreview) {
        Stroke prev = g.getStroke();
        Color prevC = g.getColor();
        g.setStroke(new BasicStroke(r.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(r.color);

        switch (r.tool) {
            case FREEHAND:
            case LINE:
                g.draw(r.shape);
                break;
            case AUTHORISATION:
                Stroke oldStroke = g.getStroke();
                float[] dash = {8.0f, 4.0f};
                g.setStroke(new BasicStroke(r.stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
                g.draw(r.shape);
                g.setStroke(oldStroke);
                break;
            case ENACTS: {
                double sourceDot = Math.max(3.5, r.stroke + 2.0);
                double targetDash = Math.max(10.0, r.stroke * 3.0);
                double dx = r.x2 - r.x1;
                double dy = r.y2 - r.y1;
                double len = Math.hypot(dx, dy);

                if (len > 1e-6) {
                    // Stop the shaft at each decoration so both ends stay visually distinct.
                    double ux = dx / len;
                    double uy = dy / len;
                    double startX = r.x1 + ux * sourceDot;
                    double startY = r.y1 + uy * sourceDot;
                    double endX = r.x2 - ux * targetDash;
                    double endY = r.y2 - uy * targetDash;
                    g.draw(new Line2D.Double(startX, startY, endX, endY));
                } else {
                    g.draw(r.shape);
                }

                if (len > 1e-6) {
                    double ux = dx / len;
                    double uy = dy / len;
                    double sourceCx = r.x1;
                    double sourceCy = r.y1;
                    double dashCx = r.x2 - ux * (targetDash / 2.0);
                    double dashCy = r.y2 - uy * (targetDash / 2.0);

                    g.fill(new Ellipse2D.Double(sourceCx - sourceDot, sourceCy - sourceDot, sourceDot * 2, sourceDot * 2));

                    Graphics2D gDash = (Graphics2D) g.create();
                    gDash.translate(dashCx, dashCy);
                    gDash.rotate(Math.atan2(dy, dx));
                    gDash.fill(new RoundRectangle2D.Double(-targetDash / 2.0, -Math.max(1.8, r.stroke / 2.0), targetDash, Math.max(3.5, r.stroke + 1.0), 2.0, 2.0));
                    gDash.dispose();
                }
                break;
            }
            case OVAL:
            case RECTANGLE:
            case ROUNDED_RECTANGLE: {
                // Draw the shape border
                g.draw(r.shape);
                // Draw text label if present
                if (r.text != null && !shouldSuppressLabelPaint(r)) {
                    Rectangle2D bounds = r.shape.getBounds2D();
                    Font f = normalizeTextFont(r.font);
                    g.setFont(f);
                    drawTextLayout(g, r.text, f, bounds, r.color != null ? r.color : g.getColor());
                }
                break;
            }
            case OBJECT_TYPE: {
                drawObjectTypeBox(g, r);
                break;
            }
            case SYSTEM: {
                drawSystemBox(g, r);
                break;
            }
            case BOUNDARY: {
                drawBoundaryBox(g, r);
                break;
            }
            case ACTOR: {
                g.draw(r.shape);
                if (r.text != null && !shouldSuppressLabelPaint(r)) {
                    Rectangle2D bounds = r.shape.getBounds2D();
                    Font f = normalizeTextFont(r.font);
                    g.setFont(f);
                    String label = r.text.split("\\R", 2)[0];
                    FontMetrics fm = g.getFontMetrics(f);
                    int textWidth = fm.stringWidth(label);
                    float textX = (float) (bounds.getCenterX() - textWidth / 2.0);
                    float textY = (float) (bounds.getY() + bounds.getHeight() + fm.getAscent() + 4.0);
                    g.setColor(r.color != null ? r.color : g.getColor());
                    g.drawString(label, textX, textY);
                }
                break;
            }
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_EMPTY:
            case ARROW_OPEN:
            case IMPACT:
            case REFERENCE:
            case INITIAL_TRANSITION:
            case FINAL_TRANSITION: {
                if (isSelfLoopTransition(r) && (r.shape instanceof Arc2D || r.shape instanceof CubicCurve2D)) {
                    Point2D.Double tangentStart;
                    if (r.shape instanceof CubicCurve2D) {
                        CubicCurve2D loop = (CubicCurve2D) r.shape;
                        g.draw(loop);
                        tangentStart = new Point2D.Double(loop.getCtrlX2(), loop.getCtrlY2());
                    } else {
                        Arc2D loop = (Arc2D) r.shape;
                        g.draw(loop);
                        tangentStart = arcTangentTail(loop);
                    }

                    if (r.tool == Tool.INITIAL_TRANSITION) {
                        double circleRadius = Math.max(6, r.stroke * 2);
                        g.fill(new Ellipse2D.Double(r.x1 - circleRadius, r.y1 - circleRadius, circleRadius * 2, circleRadius * 2));
                    }

                    double tangentStartX = tangentStart.x;
                    double tangentStartY = tangentStart.y;
                    drawArrowHead(g, tangentStartX, tangentStartY, r.x2, r.y2, r.tool);

                    if (r.tool == Tool.FINAL_TRANSITION) {
                        double circleRadius = Math.max(8, r.stroke * 2.5);
                        g.draw(new Ellipse2D.Double(r.x2 - circleRadius, r.y2 - circleRadius, circleRadius * 2, circleRadius * 2));
                        double crossSize = circleRadius * 0.6;
                        g.draw(new Line2D.Double(r.x2 - crossSize, r.y2 - crossSize, r.x2 + crossSize, r.y2 + crossSize));
                        g.draw(new Line2D.Double(r.x2 - crossSize, r.y2 + crossSize, r.x2 + crossSize, r.y2 - crossSize));
                    }

                    if (r.text != null && !r.text.isBlank() && !shouldSuppressLabelPaint(r)) {
                        Font f = normalizeTextFont(r.font);
                        g.setFont(f);
                        if (r.tool == Tool.ARROW_OPEN || r.tool == Tool.ARROW_FILLED || r.tool == Tool.ARROW_EMPTY || r.tool == Tool.ARROW_DIAMOND
                            || r.tool == Tool.INITIAL_TRANSITION || r.tool == Tool.FINAL_TRANSITION) {
                            if (r.shape instanceof Arc2D) {
                                drawSelfLoopTransitionLabel(g, (Arc2D) r.shape, r.text, f);
                            } else {
                                drawTransitionLabel(g, r, f);
                            }
                        } else if (r.tool == Tool.IMPACT || r.tool == Tool.REFERENCE) {
                            drawReferenceLabel(g, r, f);
                        }
                    }
                    break;
                }

                double tipX = r.x2;
                double tipY = r.y2;

                // For FINAL_TRANSITION, place the arrow tip before the circled X with a visible gap.
                if (r.tool == Tool.FINAL_TRANSITION) {
                    double circleRadius = Math.max(8, r.stroke * 2.5);
                    double gap = Math.max(4, r.stroke * 1.2);
                    double dx = r.x2 - r.x1;
                    double dy = r.y2 - r.y1;
                    double len = Math.hypot(dx, dy);
                    if (len > 1e-6) {
                        double ux = dx / len;
                        double uy = dy / len;
                        tipX = r.x2 - ux * (circleRadius + gap);
                        tipY = r.y2 - uy * (circleRadius + gap);
                    }
                }

                // compute base of arrow head and draw shaft only to that base
                Point2D.Double baseAll = computeArrowBase(r.x1, r.y1, tipX, tipY, r.stroke);
                
                // For INITIAL_TRANSITION, adjust shaft start to account for filled circle
                double shaftStartX = r.x1;
                double shaftStartY = r.y1;
                if (r.tool == Tool.INITIAL_TRANSITION) {
                    double circleRadius = Math.max(6, r.stroke * 2);
                    double dx = r.x2 - r.x1;
                    double dy = r.y2 - r.y1;
                    double len = Math.hypot(dx, dy);
                    if (len > 1e-6) {
                        double ux = dx / len;
                        double uy = dy / len;
                        shaftStartX = r.x1 + ux * circleRadius;
                        shaftStartY = r.y1 + uy * circleRadius;
                    }
                }

                if (r.tool == Tool.ARROW_DIAMOND) {
                    double dx = tipX - shaftStartX;
                    double dy = tipY - shaftStartY;
                    double len = Math.hypot(dx, dy);
                    if (len > 1e-6) {
                        double ux = dx / len;
                        double uy = dy / len;
                        double diamondLen = Math.max(10.0, 8.0 + r.stroke * 2.4);
                        baseAll = new Point2D.Double(tipX - ux * diamondLen, tipY - uy * diamondLen);
                    }
                }
                
                Line2D shaftAll = new Line2D.Double(shaftStartX, shaftStartY, baseAll.x, baseAll.y);
                g.draw(shaftAll);
                
                // Draw start decoration for INITIAL_TRANSITION (filled circle)
                if (r.tool == Tool.INITIAL_TRANSITION) {
                    double circleRadius = Math.max(6, r.stroke * 2);
                    g.fill(new Ellipse2D.Double(r.x1 - circleRadius, r.y1 - circleRadius, circleRadius * 2, circleRadius * 2));
                }
                
                // draw head using the same stroke so geometry matches
                drawArrowHead(g, r.x1, r.y1, tipX, tipY, r.tool);
                
                // Draw end decoration for FINAL_TRANSITION (circle with cross)
                if (r.tool == Tool.FINAL_TRANSITION) {
                    double circleRadius = Math.max(8, r.stroke * 2.5);
                    g.draw(new Ellipse2D.Double(r.x2 - circleRadius, r.y2 - circleRadius, circleRadius * 2, circleRadius * 2));
                    // Draw cross inside circle
                    double crossSize = circleRadius * 0.6;
                    g.draw(new Line2D.Double(r.x2 - crossSize, r.y2 - crossSize, r.x2 + crossSize, r.y2 + crossSize));
                    g.draw(new Line2D.Double(r.x2 - crossSize, r.y2 + crossSize, r.x2 + crossSize, r.y2 - crossSize));
                }
                if (r.text != null && !r.text.isBlank() && !shouldSuppressLabelPaint(r)) {
                    Font f = normalizeTextFont(r.font);
                    g.setFont(f);
                    if (r.tool == Tool.ARROW_OPEN || r.tool == Tool.ARROW_FILLED || r.tool == Tool.ARROW_EMPTY || r.tool == Tool.ARROW_DIAMOND 
                        || r.tool == Tool.INITIAL_TRANSITION || r.tool == Tool.FINAL_TRANSITION) {
                        drawTransitionLabel(g, r, f);
                    } else if (r.tool == Tool.IMPACT || r.tool == Tool.REFERENCE) {
                        drawReferenceLabel(g, r, f);
                    }
                }
                break;
            }
            case STATE: {
                g.draw(r.shape);
                Rectangle2D bounds = r.shape.getBounds2D();
                if (r.text != null && !shouldSuppressLabelPaint(r)) {
                    Font f = normalizeTextFont(r.font);
                    g.setFont(f);
                    drawTextLayout(g, r.text, f, bounds, r.color != null ? r.color : g.getColor());
                }
                break;
            }
            case TEXT: {
                // draw multi-line / wrapped text within the shape bounds
                try {
                    Rectangle2D bounds = r.shape.getBounds2D();
                    if (r.text != null) {
                        Font f = normalizeTextFont(r.font);
                        g.setFont(f);
                        drawTextLayout(g, r.text, f, bounds, r.color != null ? r.color : g.getColor());
                    }
                } catch (Exception ex) {
                    // fallback: draw shape
                    try { g.draw(r.shape); } catch (Exception ignore) {}
                }
                break;
            }
            default:
                g.draw(r.shape);
        }
        g.setColor(prevC);
        g.setStroke(prev);
    }

    @Override
    protected void paintComponent(Graphics gg) {
        super.paintComponent(gg);
        ensureBuffer();
        Graphics2D g = (Graphics2D) gg.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(zoomScale, zoomScale);
        g.drawImage(buf, 0, 0, this);

        for (ConnectorCrossing crossing : computeConnectorCrossings()) {
            drawConnectorBridge(g, crossing.top, crossing.point);
        }

        // draw preview on top
        if (preview != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // semi-transparent preview
            Composite prevComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            drawRecord(g, preview, true);
            g.setComposite(prevComp);
        }

        // draw selection handles
        if (selectedIndex >= 0 && selectedIndex < shapes.size() && currentTool != Tool.PAN) {
            ShapeRecord sel = shapes.get(selectedIndex);

            g.setColor(Color.BLUE);
            g.setStroke(new BasicStroke(1f));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.draw(sel.shape);

            Rectangle2D b = getShapeBounds(sel);
            if (isConnectorTool(sel.tool)) {
                double h = HANDLE_SIZE + 2;
                Ellipse2D start = new Ellipse2D.Double(sel.x1 - h / 2.0, sel.y1 - h / 2.0, h, h);
                Ellipse2D end = new Ellipse2D.Double(sel.x2 - h / 2.0, sel.y2 - h / 2.0, h, h);
                g.setColor(Color.WHITE);
                g.fill(start);
                g.fill(end);
                g.setColor(Color.BLUE);
                g.draw(start);
                g.draw(end);
            } else if (b != null && sel.tool != Tool.ACTOR && !isConstrainedSizeTool(sel.tool)) {
                double hx = b.getX(), hy = b.getY(), hw = b.getWidth(), hh = b.getHeight();
                double mx = hx + hw / 2.0, my = hy + hh / 2.0;
                double edgeHandle = Math.max(8, HANDLE_SIZE - 2);
                Rectangle2D[] handles = new Rectangle2D[] {
                    new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tl
                    new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tr
                    new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // br
                    new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // bl
                    new Rectangle2D.Double(mx - edgeHandle/2, hy - edgeHandle/2, edgeHandle, edgeHandle), // top
                    new Rectangle2D.Double(hx + hw - edgeHandle/2, my - edgeHandle/2, edgeHandle, edgeHandle), // right
                    new Rectangle2D.Double(mx - edgeHandle/2, hy + hh - edgeHandle/2, edgeHandle, edgeHandle), // bottom
                    new Rectangle2D.Double(hx - edgeHandle/2, my - edgeHandle/2, edgeHandle, edgeHandle) // left
                };
                g.setColor(Color.WHITE);
                for (Rectangle2D h : handles) {
                    g.fill(h);
                    g.setColor(Color.BLUE);
                    g.draw(h);
                    g.setColor(Color.WHITE);
                }
            }
        }
        g.dispose();
    }

    private void drawArrowHead(Graphics2D g, double x1, double y1, double x2, double y2, Tool kind) {
        // compute unit vector along line
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double ux = dx / len, uy = dy / len;
        double px = -uy, py = ux; // perp

        double headLen = Math.max(8, 6 + strokeWidth * 2);
        double headWidth = Math.max(12, 10 + strokeWidth * 1.5);

        // base of head
        double bx = x2 - ux * headLen;
        double by = y2 - uy * headLen;

        // two side points
        double sx1 = bx + px * (headWidth / 2.0);
        double sy1 = by + py * (headWidth / 2.0);
        double sx2 = bx - px * (headWidth / 2.0);
        double sy2 = by - py * (headWidth / 2.0);

        Paint prev = g.getPaint();
        switch (kind) {
            case ARROW_FILLED: {
                Path2D p = new Path2D.Double();
                p.moveTo(x2, y2);
                p.lineTo(sx1, sy1);
                p.lineTo(sx2, sy2);
                p.closePath();
                g.fill(p);
                break;
            }
            case ARROW_DIAMOND: {
                // Balanced diamond: equal-looking longitudinal and lateral diagonals.
                double diamondLen = Math.max(10.0, headLen * 1.05);
                double halfDiag = diamondLen / 2.0;

                double mx = x2 - ux * halfDiag;
                double my = y2 - uy * halfDiag;
                double rx = x2 - ux * diamondLen;
                double ry = y2 - uy * diamondLen;
                double lx = mx + px * halfDiag;
                double ly = my + py * halfDiag;
                double rxSide = mx - px * halfDiag;
                double rySide = my - py * halfDiag;

                Path2D d = new Path2D.Double();
                d.moveTo(x2, y2);
                d.lineTo(lx, ly);
                d.lineTo(rx, ry);
                d.lineTo(rxSide, rySide);
                d.closePath();
                g.draw(d);
                break;
            }
            case ARROW_OPEN:
            case INITIAL_TRANSITION:
            case FINAL_TRANSITION:
            case IMPACT:
            case REFERENCE: {
                // draw two lines forming open head
                g.draw(new Line2D.Double(x2, y2, sx1, sy1));
                g.draw(new Line2D.Double(x2, y2, sx2, sy2));
                break;
            }
            case ARROW_EMPTY: {
                Path2D tri = new Path2D.Double();
                tri.moveTo(x2, y2);  
                tri.lineTo(sx1, sy1); 
                tri.lineTo(sx2, sy2); 
                tri.closePath();
                g.draw(tri); 
                break;
            }
            default:
                break;
        }
    }

    private void drawSystemBox(Graphics2D g, ShapeRecord r) {
        Rectangle2D bounds = r.shape.getBounds2D();
        double x = bounds.getX();
        double y = bounds.getY();
        double w = bounds.getWidth();
        double h = bounds.getHeight();
        double depth = Math.max(6, Math.min(w, h) * 0.18);

        Rectangle2D front = new Rectangle2D.Double(x, y, w, h);
        Polygon top = new Polygon();
        top.addPoint((int) x, (int) y);
        top.addPoint((int) (x + depth), (int) (y - depth));
        top.addPoint((int) (x + w + depth), (int) (y - depth));
        top.addPoint((int) (x + w), (int) y);

        Polygon side = new Polygon();
        side.addPoint((int) (x + w), (int) y);
        side.addPoint((int) (x + w + depth), (int) (y - depth));
        side.addPoint((int) (x + w + depth), (int) (y + h - depth));
        side.addPoint((int) (x + w), (int) (y + h));

        Color base = r.color != null ? r.color : g.getColor();
        Color topShade = base.brighter();
        Color sideShade = base.darker();

        Paint prevPaint = g.getPaint();
        g.setPaint(topShade);
        g.fill(top);
        g.setPaint(sideShade);
        g.fill(side);
        g.setPaint(prevPaint);

        g.draw(front);
        g.draw(top);
        g.draw(side);

        if (r.text != null && !shouldSuppressLabelPaint(r)) {
            Font f = normalizeTextFont(r.font);
            g.setFont(f);
            drawTextLayout(g, r.text, f, front, base);
        }
    }

    private void drawBoundaryBox(Graphics2D g, ShapeRecord r) {
        Rectangle2D bounds = r.shape.getBounds2D();
        double x = bounds.getX();
        double y = bounds.getY();
        double w = bounds.getWidth();
        double h = bounds.getHeight();

        String label = (r.text != null && !r.text.isBlank()) ? r.text.split("\\R", 2)[0].trim() : "Boundary";
        Font baseFont = normalizeTextFont(r.font);

        double tabHeight = boundaryTabHeight(h);
        double tabWidth = boundaryTabWidth(w);

        Font fitFont = baseFont;
        FontMetrics fitFm = g.getFontMetrics(fitFont);

        String drawLabel = label;
        if (fitFm.stringWidth(drawLabel) + 16 > tabWidth) {
            String ellipsis = "...";
            int maxTextWidth = (int) Math.max(8, tabWidth - 16 - fitFm.stringWidth(ellipsis));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                if (fitFm.stringWidth(sb.toString() + ch) > maxTextWidth) break;
                sb.append(ch);
            }
            drawLabel = sb.toString().trim() + ellipsis;
        }
        
        // Main rectangle
        Rectangle2D mainRect = new Rectangle2D.Double(x, y + tabHeight, w, h - tabHeight);
        
        // Tab at top-left
        Rectangle2D tab = new Rectangle2D.Double(x, y, tabWidth, tabHeight);
        
        Color base = r.color != null ? r.color : g.getColor();
        g.setColor(base);
        
        // Draw main rectangle
        g.draw(mainRect);
        
        // Draw tab
        g.draw(tab);
        
        // Draw connecting line between tab and main rect (if they don't touch perfectly)
        g.drawLine((int)(x + tabWidth), (int)(y + tabHeight), (int)x, (int)(y + tabHeight));
        
        if (shouldSuppressLabelPaint(r)) {
            return;
        }

        // Draw text in tab area
        g.setFont(fitFont);
        FontMetrics fm = g.getFontMetrics(fitFont);
        int textWidth = fm.stringWidth(drawLabel);
        float textX = (float) (x + (tabWidth - textWidth) / 2.0);
        float textY = (float) (y + (tabHeight - fm.getHeight()) / 2.0 + fm.getAscent());
        g.setColor(base);
        g.drawString(drawLabel, textX, textY);
    }

    private void drawAlignedConnectorLabel(Graphics2D g, ShapeRecord r, Font f, String[] lines, boolean underlineFirst) {
        if (r == null || lines == null || lines.length == 0) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics(f);

        double dx = r.x2 - r.x1;
        double dy = r.y2 - r.y1;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            g2.dispose();
            return;
        }

        double angle = Math.atan2(dy, dx);
        double nx = -dy / len;
        double ny = dx / len;
        if (angle > Math.PI / 2 || angle < -Math.PI / 2) {
            angle += Math.PI;
            nx = -nx;
            ny = -ny;
        }

        double offset = Math.max(10.0, fm.getHeight() * 0.6);
        double midX = (r.x1 + r.x2) / 2.0 + nx * offset;
        double midY = (r.y1 + r.y2) / 2.0 + ny * offset;

        g2.translate(midX, midY);
        g2.rotate(angle);

        int lineHeight = fm.getHeight();
        float baseline = (float) (-(lines.length * lineHeight) / 2.0 + fm.getAscent());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] != null ? lines[i].trim() : "";
            if (line.isEmpty()) continue;
            int lineWidth = fm.stringWidth(line);
            float x = -lineWidth / 2.0f;
            float y = baseline + i * lineHeight;
            g2.drawString(line, x, y);
            if (underlineFirst && i == 0) {
                int underlineY = Math.round(y + 2);
                g2.drawLine(Math.round(x), underlineY, Math.round(x + lineWidth), underlineY);
            }
        }
        g2.dispose();
    }

    private void drawTransitionLabel(Graphics2D g, ShapeRecord r, Font f) {
        if (r.text == null) return;
        String text = formatTransitionLabel(r.text);
        if (text.isEmpty()) return;
        drawAlignedConnectorLabel(g, r, f, new String[] { text }, false);
    }

    private void drawSelfLoopTransitionLabel(Graphics2D g, Arc2D arc, String text, Font f) {
        if (arc == null || text == null) return;
        String label = formatTransitionLabel(text);
        if (label.isEmpty()) return;

        Font font = f != null ? f : g.getFont();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);

        // Place near the lower-right interior of the loop so it stays inside
        // the loop and out of the state body.
        double angleDeg = 335.0;
        double rad = Math.toRadians(angleDeg);
        double rx = arc.getWidth() / 2.0;
        double ry = arc.getHeight() / 2.0;
        double cx = arc.getX() + rx;
        double cy = arc.getY() + ry;

        double px = cx + rx * Math.cos(rad);
        double py = cy - ry * Math.sin(rad);

        // Pull label inward from the arc along ellipse normal direction.
        double nx = Math.cos(rad) / Math.max(1e-6, rx);
        double ny = -Math.sin(rad) / Math.max(1e-6, ry);
        double nLen = Math.hypot(nx, ny);
        if (nLen > 1e-6) {
            nx /= nLen;
            ny /= nLen;
        }
        double offset = Math.max(16.0, fm.getHeight() * 0.9);
        px -= nx * offset;
        py -= ny * offset;

        int textWidth = fm.stringWidth(label);
        float tx = (float) (px - textWidth / 2.0);
        float ty = (float) (py + fm.getAscent() / 2.0);
        g.drawString(label, tx, ty);
    }

    private void drawReferenceLabel(Graphics2D g, ShapeRecord r, Font f) {
        if (r.text == null) return;
        String[] lines = r.text.split("\\R");
        String label = lines.length > 0 ? lines[0].trim() : "";
        String qualifier = lines.length > 1 ? lines[1].trim() : "";

        boolean underline = false;
        if (r.tool == Tool.REFERENCE && label.startsWith("*")) {
            underline = true;
            label = label.substring(1).trim();
        }

        if (qualifier.isEmpty()) {
            drawAlignedConnectorLabel(g, r, f, new String[] { label }, underline);
        } else {
            drawAlignedConnectorLabel(g, r, f, new String[] { label, qualifier }, underline);
        }
    }

    private void drawObjectTypeBox(Graphics2D g, ShapeRecord r) {
        Rectangle2D bounds = r.shape.getBounds2D();
        double x = bounds.getX();
        double y = bounds.getY();
        double w = bounds.getWidth();
        double h = bounds.getHeight();

        Color base = r.color != null ? r.color : g.getColor();
        g.setColor(base);

        g.draw(bounds);

        String text = r.text != null ? r.text : getDefaultLabelForTool(Tool.OBJECT_TYPE);
        String[] lines = text.split("\\R");
        String name = lines.length > 0 && !lines[0].isBlank() ? lines[0].trim() : "Object";
        boolean suppressText = shouldSuppressLabelPaint(r);

        Font baseFont = normalizeTextFont(r.font);
        Font nameFont = baseFont.deriveFont(Font.BOLD);
        g.setFont(nameFont);
        FontMetrics nameFm = g.getFontMetrics(nameFont);

        int nameHeight = nameFm.getHeight() + 6;
        int maxNameHeight = (int) Math.max(16, Math.min(h * 0.4, nameHeight));
        int dividerY = (int) (y + maxNameHeight);

        g.drawLine((int) x, dividerY, (int) (x + w), dividerY);

        if (!suppressText) {
            int nameWidth = nameFm.stringWidth(name);
            float nameX = (float) (x + (w - nameWidth) / 2.0);
            float nameY = (float) (y + (maxNameHeight - nameFm.getHeight()) / 2.0 + nameFm.getAscent());
            g.drawString(name, nameX, nameY);
        }

        g.setFont(baseFont);
        FontMetrics fm = g.getFontMetrics(baseFont);
        float attrX = (float) (x + 6);
        float attrY = dividerY + fm.getAscent() + 4;

        if (suppressText) {
            return;
        }

        for (int i = 1; i < lines.length; i++) {
            String attr = lines[i].trim();
            if (attr.isEmpty()) {
                attrY += fm.getHeight();
                continue;
            }
            boolean underline = false;
            if (attr.startsWith("*")) {
                underline = true;
                attr = attr.substring(1).trim();
            }
            g.drawString(attr, attrX, attrY);
            if (underline && !attr.isEmpty()) {
                int textWidth = fm.stringWidth(attr);
                int underlineY = (int) (attrY + 2);
                g.drawLine((int) attrX, underlineY, (int) attrX + textWidth, underlineY);
            }
            attrY += fm.getHeight() + 2;
            if (attrY > y + h - 4) break;
        }
    }

    private Shape buildBoundaryShape(double x, double y, double w, double h) {
        // For preview/selection purposes, return a composite path of rectangle + tab
        double tabWidth = BOUNDARY_TAB_WIDTH;
        double tabHeight = BOUNDARY_TAB_HEIGHT;
        
        Path2D p = new Path2D.Double();
        // Main rectangle
        p.moveTo(x, y + tabHeight);
        p.lineTo(x, y + h);
        p.lineTo(x + w, y + h);
        p.lineTo(x + w, y + tabHeight);
        p.lineTo(x + tabWidth, y + tabHeight);
        p.lineTo(x + tabWidth, y);
        p.lineTo(x, y);
        p.closePath();
        
        return p;
    }

    private double boundaryTabWidth(double w) {
        return BOUNDARY_TAB_WIDTH;
    }

    private double boundaryTabHeight(double h) {
        return BOUNDARY_TAB_HEIGHT;
    }


    /**
     * Draw text with simple word-wrapping inside the given bounds using FontMetrics.
     * Text is horizontally centered within the bounds.
     * This avoids dependencies on java.text.LineBreakMeasurer/TextLayout so it compiles
     * cleanly in minimal module configurations.
     */
    private void drawTextLayout(Graphics2D g, String text, Font font, Rectangle2D bounds, Color color) {
        if (text == null || text.isEmpty() || bounds == null) return;
        g.setFont(font != null ? font : g.getFont());
        g.setColor(color != null ? color : g.getColor());
        FontMetrics fm = g.getFontMetrics(g.getFont());
        int wrapWidth = Math.max(4, (int) bounds.getWidth() - 8);
        java.util.List<String> lines = new ArrayList<>();
        java.util.List<Boolean> addLeadingAfter = new ArrayList<>();

        String[] paragraphs = text.split("\r?\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p].trim();
            int linesBefore = lines.size();

            if (paragraph.isEmpty()) {
                lines.add("");
                addLeadingAfter.add(false);
            } else {
                String[] words = paragraph.split("\\s+");
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < words.length; i++) {
                    String word = words[i];
                    String test = line.length() == 0 ? word : line + " " + word;
                    int w = fm.stringWidth(test);
                    if (w > wrapWidth && line.length() > 0) {
                        lines.add(line.toString());
                        addLeadingAfter.add(false);
                        line.setLength(0);
                        line.append(word);
                    } else {
                        if (line.length() > 0) line.append(' ');
                        line.append(word);
                    }
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                    addLeadingAfter.add(false);
                }
            }

            if (p < paragraphs.length - 1 && lines.size() > linesBefore) {
                int lastIndex = lines.size() - 1;
                addLeadingAfter.set(lastIndex, true);
            }
        }

        if (lines.isEmpty()) return;

        double totalHeight = 0;
        for (int i = 0; i < lines.size(); i++) {
            totalHeight += fm.getHeight();
            if (Boolean.TRUE.equals(addLeadingAfter.get(i))) {
                totalHeight += fm.getLeading();
            }
        }

        double minBaseline = bounds.getY() + 4.0 + fm.getAscent();
        float y = (float) (bounds.getY() + (bounds.getHeight() - totalHeight) / 2.0 + fm.getAscent());
        if (y < minBaseline) y = (float) minBaseline;

        for (int i = 0; i < lines.size(); i++) {
            String lineStr = lines.get(i);
            if (!lineStr.isEmpty()) {
                int lineWidth = fm.stringWidth(lineStr);
                float x = (float) (bounds.getX() + (bounds.getWidth() - lineWidth) / 2.0);
                g.drawString(lineStr, x, y);
            }
            y += fm.getHeight();
            if (Boolean.TRUE.equals(addLeadingAfter.get(i))) {
                y += fm.getLeading();
            }
            if (y > bounds.getY() + bounds.getHeight()) return;
        }
    }

    public void clear() {
        shapes.clear();
        if (buf != null) {
            Graphics2D g = buf.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, buf.getWidth(), buf.getHeight());
            g.dispose();
        }
        selectedIndex = -1;
        selectAllActive = false;
        repaint();
    }

    public void copySelectedShape() {
        if (selectAllActive && !shapes.isEmpty()) {
            selectedIndex = shapes.size() - 1;
        }
        if (selectedIndex < 0 || selectedIndex >= shapes.size()) return;
        clipboardRecord = copyShapeRecord(shapes.get(selectedIndex));
        pasteSerial = 0;
        statusConsumer.accept("Copied");
    }

    public void cutSelectedShape() {
        if (selectedIndex < 0 || selectedIndex >= shapes.size()) return;
        copySelectedShape();
        deleteSelectedShape();
        statusConsumer.accept("Cut");
    }

    public void pasteClipboardShape() {
        if (clipboardRecord == null) return;

        selectAllActive = false;

        pasteSerial++;
        int offset = 20 * pasteSerial;
        ShapeRecord pasted = createPastedRecord(clipboardRecord, offset, offset);
        if (pasted == null) return;

        if (model != null) {
            ReMoDeLEntity ent = entityFromShape(pasted);
            ent.setId(java.util.UUID.randomUUID().toString());
            model.addEntity(ent);
            SwingUtilities.invokeLater(() -> {
                Integer idx = idToIndex.get(ent.getId());
                if (idx != null) {
                    selectedIndex = idx;
                    repaint();
                }
            });
        } else {
            shapes.add(pasted);
            selectedIndex = shapes.size() - 1;
            redrawBuffer();
            repaint();
        }
        statusConsumer.accept("Pasted");
    }

    private ShapeRecord createPastedRecord(ShapeRecord source, int dx, int dy) {
        if (source == null) return null;

        Shape moved;
        if (source.tool == Tool.TEXT) {
            moved = new Rectangle2D.Double(
                Math.min(source.x1 + dx, source.x2 + dx),
                Math.min(source.y1 + dy, source.y2 + dy),
                Math.abs(source.x2 - source.x1),
                Math.abs(source.y2 - source.y1)
            );
        } else {
            moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(source.shape);
        }

        return new ShapeRecord(
            source.tool,
            moved,
            source.color,
            source.stroke,
            source.x1 + dx,
            source.y1 + dy,
            source.x2 + dx,
            source.y2 + dy,
            source.text,
            source.font,
            null,
            null,
            null,
            null
        );
    }

    public void saveDrawing(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("File cannot be null");
        CanvasSnapshot snapshot = new CanvasSnapshot(new ArrayList<>(shapes));
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
            out.writeObject(snapshot);
            System.out.println("Drawing saved successfully.");
        }
    }

    public void loadDrawing(File file) throws IOException, ClassNotFoundException {
        if (file == null) throw new IllegalArgumentException("File cannot be null");
        CanvasSnapshot snapshot;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            snapshot = (CanvasSnapshot) in.readObject();
        }
        setModel(null);
        shapes.clear();
        idToIndex.clear();
        undoManager.discardAllEdits();
        selectedIndex = -1;
        preview = null;
        if (snapshot != null && snapshot.shapes != null) {
            shapes.addAll(snapshot.shapes);
        }
        redrawBuffer();
        repaint();
        updateUndoRedoState();
    }

    public void setDrawColor(Color c) {
        if (c != null) drawColor = c;
    }

    public void setStrokeWidth(float w) {
        strokeWidth = Math.max(1f, w);
    }

    public void setCurrentTool(Tool t) {
        if (t != null) {
            Tool old = this.currentTool;
            this.currentTool = t;
            // when switching to select, pan, or text, clear preview
            if (t == Tool.SELECT || t == Tool.PAN || t == Tool.TEXT) preview = null;
            // notify listeners so UI can update
            firePropertyChange("currentTool", old, t);
        }
    }

    // getter for current tool (used by UI)
    public Tool getCurrentTool() {
        return currentTool;
    }

    // getter for draw color so toolbar/text inserter can reuse it
    public Color getDrawColor() {
        return drawColor;
    }

    public void addStatusConsumer(Consumer<String> c) {
        this.statusConsumer = c;
    }

     /**
+     * Insert a default-sized shape of the given tool at the center of the canvas.
+     * Uses the same ShapeRecord creation logic as dragging would.
+     */
    public void addDefaultShape(Tool t) {

        if (t == null) return;
        int dw = Math.min(200, Math.max(40, getWidth() / 6));
        int dh = Math.min(150, Math.max(30, getHeight() /10));
        int x = Math.max(10, (getWidth() - dw) / 2);
        int y = Math.max(10, (getHeight() - dh) / 2);

        ShapeRecord r = createRecordFromTool(t, drawColor, strokeWidth, x, y, x + dw, y + dh);
        if (r != null) {
            shapes.add(r);
            redrawBuffer();
            repaint();
        }
    }

    /**
     * Add a default text item centered on the canvas.
     */
    public void addDefaultText(String text) {
        if (text == null || text.isEmpty()) text = "Text";
        int dw = Math.min(300, Math.max(80, getWidth() / 6));
        int dh = Math.min(120, Math.max(20, getHeight() /12));
        int x = Math.max(10, (getWidth() - dw) / 2);
        int y = Math.max(10, (getHeight() - dh) / 2);

        // Create a text shape (using a rectangle as placeholder)
        Font f = defaultTextFont();
        ShapeRecord r = ShapeRecord.textRecord(text, f, drawColor, strokeWidth, x, y, dw,  dh);
        shapes.add(r);
        redrawBuffer();
        repaint();
    }

    /**
     * Add text at explicit bounds (x,y,w,h).
     */
    public void addTextAt(String text, int x, int y, int w, int h) {
        if (text == null) text = "";
        Font f = defaultTextFont();
        // If a model is present, create a model entity and let the model listener populate the canvas.
        if (model != null) {
            ReMoDeLEntity ent = new ReMoDeLEntity();
            ent.setType("text");
            ent.put("x1", x);
            ent.put("y1", y);
            ent.put("x2", x + w);
            ent.put("y2", y + h);
            ent.put("text", text);
            ent.put("fontName", f.getName());
            ent.put("fontStyle", f.getStyle());
            ent.put("fontSize", f.getSize());
            ent.put("colorRGB", drawColor.getRGB());
            // add to model (will trigger listener to update canvas)
            model.addEntity(ent);
            // find resulting index and register undo
            Integer idx = idToIndex.get(ent.getId());
            if (idx == null) {
                // fallback: try to locate the entity in shapes
                for (int i = 0; i < shapes.size(); i++) {
                    ShapeRecord rr = shapes.get(i);
                    if (ent.getId().equals(rr.entityId)) { idx = i; break; }
                }
            }
            if (idx != null) {
                addUndoableEdit(new TextCreateEdit(idx, copyShapeRecord(shapes.get(idx)), ent.copy()));
            }
        } else {
            ShapeRecord r = ShapeRecord.textRecord(text, f, drawColor, strokeWidth, x, y, w, h);
            shapes.add(r);
            // create undoable edit for text creation
            int idx = shapes.size() - 1;
            addUndoableEdit(new TextCreateEdit(idx, copyShapeRecord(r)));
            redrawBuffer();
            repaint();
        }
    }

    /**
     * Start inline editing of a text shape at given index. Creates a JTextField overlay.
     */
    private void startEditingText(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.TEXT) return;
        selectedIndex = index;

        Rectangle2D b = getShapeBounds(sel);
        if (b == null) return;

        final JTextArea ta = new JTextArea(sel.text != null ? sel.text : "");
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setOpaque(true);
        ta.setBackground(Color.WHITE);
        ta.setForeground(sel.color != null ? sel.color : drawColor);
        ta.setFont(zoomAwareEditorFont(sel.font));
        JScrollPane sp = new JScrollPane(ta);
        sp.setBounds(zoomedBounds(
            b.getX(),
            b.getY(),
            Math.max(40, b.getWidth()),
            Math.max(24, b.getHeight())
        ));
        this.add(sp);
        this.revalidate();
        this.repaint();
        ta.requestFocusInWindow();
        ta.selectAll();

        final boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            String txt = ta.getText();
            DrawingCanvas.this.remove(sp);
            // update the selected text record
            updateSelectedText(txt, ta.getFont(), ta.getForeground());
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        Runnable cancel = () -> {
            finished[0] = true;
            DrawingCanvas.this.remove(sp);
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        // Commit on Ctrl+Enter
        ta.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "commit");
        ta.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { finish.run(); }
        });
        // Cancel on Escape
        ta.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        ta.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancel.run(); }
        });

        ta.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                finish.run();
            }
        });
    }

    /**
     * Delete the currently selected shape, if any. 
    */
    public void deleteSelectedShape() {
        if (selectAllActive) {
            if (model != null) {
                java.util.List<String> ids = new ArrayList<>();
                for (ReMoDeLEntity entity : model.getAll()) {
                    ids.add(entity.getId());
                }
                for (String id : ids) {
                    model.removeEntity(id);
                }
            } else {
                shapes.clear();
                redrawBuffer();
                repaint();
            }
            selectedIndex = -1;
            selectAllActive = false;
            statusConsumer.accept("Deleted all");
            return;
        }

        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            ShapeRecord sel = shapes.get(selectedIndex);
            if (sel != null && sel.entityId != null && model != null) {
                // Cascade delete: remove the selected entity and every connector tied to it.
                removeEntityAndConnectedConnectors(sel);
            } else {
                removeShapeAndConnectedConnectors(sel);
                redrawBuffer();
                repaint();
            }
            selectedIndex = -1;
            selectAllActive = false;
        }
    }

    public void selectAllShapes() {
        if (shapes.isEmpty()) {
            selectedIndex = -1;
            selectAllActive = false;
            repaint();
            return;
        }
        selectAllActive = true;
        selectedIndex = shapes.size() - 1;
        statusConsumer.accept("Selected all");
        repaint();
    }

    private void removeEntityAndConnectedConnectors(ShapeRecord selected) {
        if (selected == null || selected.entityId == null || model == null) return;

        java.util.List<String> connectorIds = new ArrayList<>();
        for (ReMoDeLEntity entity : model.getAll()) {
            if (!isConnectorEntity(entity)) continue;
            Object fromObj = entity.get("fromId") != null ? entity.get("fromId") : entity.get("from");
            Object toObj = entity.get("toId") != null ? entity.get("toId") : entity.get("to");
            String fromId = fromObj != null ? String.valueOf(fromObj) : null;
            String toId = toObj != null ? String.valueOf(toObj) : null;
            if (selected.entityId.equals(fromId) || selected.entityId.equals(toId)) {
                connectorIds.add(entity.getId());
            }
        }

        model.removeEntity(selected.entityId);
        for (String connectorId : connectorIds) {
            model.removeEntity(connectorId);
        }
    }

    private void removeShapeAndConnectedConnectors(ShapeRecord selected) {
        if (selected == null) return;

        if (isConnectorTool(selected.tool)) {
            shapes.remove(selectedIndex);
            return;
        }

        String selectedLocalId = selected.localId;
        for (int i = shapes.size() - 1; i >= 0; i--) {
            ShapeRecord candidate = shapes.get(i);
            if (candidate == null || !isConnectorTool(candidate.tool)) continue;
            if (selectedLocalId == null) continue;
            if (selectedLocalId.equals(candidate.anchorFromId) || selectedLocalId.equals(candidate.anchorToId)) {
                shapes.remove(i);
            }
        }

        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            shapes.remove(selectedIndex);
        }
    }

    /**
     * Update text/font/color/size for the selected record if it's a TEXT item.
     */
    public void updateSelectedText(String newText, Font newFont, Color newColor) {
        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            ShapeRecord sel = shapes.get(selectedIndex);
            if (sel.tool != Tool.TEXT) return;
            // keep bounding rect; update text/font/color
            ShapeRecord nr = new ShapeRecord(Tool.TEXT,
               new Rectangle2D.Double(sel.x1, sel.y1, sel.x2 - sel.x1, sel.y2 - sel.y1),
               newColor != null ? newColor : sel.color,
               sel.stroke,
               sel.x1, sel.y1, sel.x2, sel.y2,
               newText != null ? newText : sel.text,
                    normalizeTextFont(newFont != null ? newFont : sel.font),
                    sel.entityId,
                    sel.localId,
                    sel.anchorFromId,
                    sel.anchorToId);
            // register undo: before -> after. If the shape is backed by a model entity, update the model
            ShapeRecord before = copyShapeRecord(sel);
            ShapeRecord after = copyShapeRecord(nr);
            if (sel.entityId != null && model != null) {
                // apply via model (listener will rebuild shapes)
                model.updateEntity(entityFromShape(after));
            } else {
                shapes.set(selectedIndex, nr);
                redrawBuffer();
                repaint();
            }
            addUndoableEdit(new TextEdit(selectedIndex, before, after));
        }
    }

     /**
     * Register an UndoableEdit for the last operation.
     * Call this from your controller/operations whenever an action should be undoable.
     */
    public void addUndoableEdit(UndoableEdit edit) {
        if (edit == null) return;
        undoManager.addEdit(edit);
        updateUndoRedoState();
    }

    public void undo() {
        if (undoManager.canUndo()) {
            undoManager.undo();
            updateUndoRedoState();
            repaint();
        }
    }

    public void redo() {
        if (undoManager.canRedo()) {
            undoManager.redo();
            updateUndoRedoState();
            repaint();
        }
    }

    public boolean canUndo() {
        return undoManager.canUndo();
    }

    public boolean canRedo() {
        return undoManager.canRedo();
    }

    public boolean hasDrawingContent() {
        return !shapes.isEmpty();
    }

    private void updateUndoRedoState() {
        // fire property changes so UI can enable/disable menu/buttons
        boolean canU = undoManager.canUndo();
        boolean canR = undoManager.canRedo();
        firePropertyChange("canUndo", !canU, canU);
        firePropertyChange("canRedo", !canR, canR);
    }
}