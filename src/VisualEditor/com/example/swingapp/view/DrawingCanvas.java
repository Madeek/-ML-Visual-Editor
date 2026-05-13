
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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
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

/**
 * Interactive drawing surface for shapes, connectors, labels, and model synchronization.
 */
public class DrawingCanvas extends JComponent {
    private BufferedImage buf;
    private Color drawColor = Color.BLACK;
    private float strokeWidth = 3f;
    private String referenceDefaultName = "member";
    private String referenceQualifier = "";
    private String impactLabel = "create";
    private DataflowKind dataflowKind = DataflowKind.OBJECT;
    private ProcessActionKind processActionKind = ProcessActionKind.INPUT;
    private String documentModelTypeName = "TASK_MODEL";
    private String loadedDocumentModelTypeName = null;
    private int lastX = -1, lastY = -1;
    private Consumer<String> statusConsumer = s -> {};
    private final UndoManager undoManager = new UndoManager();

    private AbstractUndoableEdit currentMoveEdit = null;
    private int[] currentPanGroup = null;


    private ShapeRecord copyShapeRecord(ShapeRecord r) {
        if (r == null) return null;
        if (r.tool == Tool.TEXT) {
            double x = r.x1, y = r.y1, w = r.x2 - r.x1, h = r.y2 - r.y1;
            Shape rect = new Rectangle2D.Double(x, y, w, h);
            return new ShapeRecord(Tool.TEXT, rect, r.color, r.stroke, r.x1, r.y1, r.x2, r.y2, r.text, r.font,
                    r.entityId, r.localId, r.anchorFromId, r.anchorToId);
        } else {

            ShapeRecord nr = createRecordFromTool(r.tool, r.color, r.stroke, (int) Math.round(r.x1), (int) Math.round(r.y1), (int) Math.round(r.x2), (int) Math.round(r.y2));
            if (nr != null) {
                return new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2,
                        r.text, r.font, r.entityId, r.localId, r.anchorFromId, r.anchorToId);
            }
            return r;
        }
    }


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

        rebuildShapesFromModel();


        modelListener = new ModelListener() {
            @Override
            public void modelChanged(ModelEvent e) {

                SwingUtilities.invokeLater(() -> {
                    rebuildShapesFromModel();
                });
            }
        };
        
        addMouseWheelListener(e -> {
            javax.swing.JScrollPane sp = (javax.swing.JScrollPane) SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, DrawingCanvas.this);
            if (sp == null) return;
            javax.swing.JViewport vp = sp.getViewport();
            java.awt.Point pos = vp.getViewPosition();
            int units = e.getUnitsToScroll() * 16; // scroll amount
            if (e.isShiftDown()) {
                pos.x += units;
            } else {
                pos.y += units;
            }
            
            Dimension viewSize = vp.getViewSize();
            pos.x = Math.max(0, Math.min(pos.x, Math.max(0, viewSize.width - vp.getWidth())));
            pos.y = Math.max(0, Math.min(pos.y, Math.max(0, viewSize.height - vp.getHeight())));
            vp.setViewPosition(pos);
        });
        m.addListener(modelListener);
    }

    private void rebuildShapesFromModel() {
        if (model == null) return;
        shapes.clear();
        idToIndex.clear();

        java.util.List<ReMoDeLEntity> all = model.getAll();
        Map<String, ReMoDeLEntity> entityIndex = new HashMap<>();


        for (ReMoDeLEntity e : all) {
            if (isConnectorEntity(e)) continue;
            ShapeRecord r = shapeFromEntity(e);
            if (r != null) {
                idToIndex.put(e.getId(), shapes.size());
                shapes.add(r);
            }
            entityIndex.put(e.getId(), e);
        }


        for (ReMoDeLEntity e : all) {
            if (!isConnectorEntity(e)) continue;
            ShapeRecord r = shapeFromConnector(e, entityIndex);
            if (r != null) {
                shapes.add(r);
            }
        }



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

    private String buildTransitionLabel(String event, String guard, String action) {
        String safeEvent = event != null ? event.trim() : "";
        String safeGuard = guard != null ? guard.trim() : "";
        String safeAction = action != null ? action.trim() : "";

        StringBuilder out = new StringBuilder();
        if (!safeEvent.isEmpty()) {
            out.append(safeEvent);
        }
        if (!safeGuard.isEmpty()) {
            if (out.length() > 0) out.append(' ');
            out.append('[').append(safeGuard).append(']');
        }
        if (!safeAction.isEmpty()) {
            if (out.length() > 0) out.append(" / ");
            out.append(safeAction);
        }
        return out.toString();
    }

    private String showTransitionEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Transition", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Transition", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        TransitionLabelParts initial = parseTransitionLabel(sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel eventRow = new JPanel(new BorderLayout(8, 0));
        eventRow.add(new JLabel("Event:"), BorderLayout.WEST);
        JTextField eventField = new JTextField(initial.event, 28);
        eventRow.add(eventField, BorderLayout.CENTER);
        form.add(eventRow);
        form.add(Box.createVerticalStrut(10));

        JPanel guardRow = new JPanel(new BorderLayout(8, 0));
        guardRow.add(new JLabel("Guard:"), BorderLayout.WEST);
        JTextField guardField = new JTextField(initial.guard, 28);
        guardRow.add(guardField, BorderLayout.CENTER);
        form.add(guardRow);
        form.add(Box.createVerticalStrut(10));

        JPanel actionRow = new JPanel(new BorderLayout(8, 0));
        actionRow.add(new JLabel("Action:"), BorderLayout.WEST);
        JTextArea actionField = new JTextArea(initial.action, 3, 28);
        actionField.setLineWrap(true);
        actionField.setWrapStyleWord(true);
        actionRow.add(new JScrollPane(actionField), BorderLayout.CENTER);
        form.add(actionRow);

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("Cancel");
        JButton okBtn = new JButton("OK");
        buttonRow.add(cancelBtn);
        buttonRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            result[0] = buildTransitionLabel(eventField.getText(), guardField.getText(), actionField.getText());
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        eventField.selectAll();
        dialog.setVisible(true);

        if (!committed[0]) {
            return null;
        }
        return result[0];
    }

    private static class ReferenceEditParts {
        final String name;
        final String qualifier;
        final boolean underline;

        ReferenceEditParts(String name, String qualifier, boolean underline) {
            this.name = name;
            this.qualifier = qualifier;
            this.underline = underline;
        }
    }

    private String buildReferenceLabel(String name, String qualifier, boolean underline) {
        String safeName = name != null && !name.isBlank() ? name.trim() : referenceDefaultName;
        if (safeName.isBlank()) safeName = "member";
        StringBuilder out = new StringBuilder();
        if (underline) out.append('*');
        out.append(safeName);

        String safeQualifier = qualifier != null ? qualifier.trim() : "";
        if (!safeQualifier.isEmpty()) {
            out.append("\n{").append(safeQualifier).append('}');
        }
        return out.toString();
    }

    private ReferenceEditParts parseReferenceParts(String text) {
        String[] lines = text != null ? text.split("\\R", -1) : new String[0];
        String name = referenceDefaultName;
        String qualifier = referenceQualifier;
        boolean underline = false;

        if (lines.length > 0 && !lines[0].isBlank()) {
            name = lines[0].trim();
            if (name.startsWith("*")) {
                underline = true;
                name = name.substring(1).trim();
            }
        }

        if (lines.length > 1 && !lines[1].isBlank()) {
            qualifier = lines[1].trim();
        }

        if (qualifier.startsWith("{") && qualifier.endsWith("}") && qualifier.length() >= 2) {
            qualifier = qualifier.substring(1, qualifier.length() - 1).trim();
        }

        return new ReferenceEditParts(name, qualifier, underline);
    }

    private String showReferenceEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Reference", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Reference", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        ReferenceEditParts initial = parseReferenceParts(sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.add(new JLabel("Reference name:"), BorderLayout.WEST);
        JTextField nameField = new JTextField(initial.name, 28);
        nameRow.add(nameField, BorderLayout.CENTER);
        form.add(nameRow);
        form.add(Box.createVerticalStrut(10));

        JPanel qualifierRow = new JPanel(new BorderLayout(8, 0));
        qualifierRow.add(new JLabel("Qualifier:"), BorderLayout.WEST);
        JTextField qualifierField = new JTextField(initial.qualifier, 28);
        qualifierRow.add(qualifierField, BorderLayout.CENTER);
        form.add(qualifierRow);
        form.add(Box.createVerticalStrut(10));

        javax.swing.JCheckBox underlineBox = new javax.swing.JCheckBox("Underline reference name", initial.underline);
        form.add(underlineBox);

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("Cancel");
        JButton okBtn = new JButton("OK");
        buttonRow.add(cancelBtn);
        buttonRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            result[0] = buildReferenceLabel(nameField.getText(), qualifierField.getText(), underlineBox.isSelected());
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        nameField.selectAll();
        dialog.setVisible(true);

        if (!committed[0]) {
            return null;
        }
        return result[0];
    }

    private String showImpactEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Impact", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Impact", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        String current = sel.text != null && !sel.text.isBlank() ? sel.text.trim() : getDefaultLabelForTool(sel.tool);
        String[] options = new String[] { "create", "read", "update", "delete" };

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel form = new JPanel(new BorderLayout(8, 8));
        form.add(new JLabel("Impact kind:"), BorderLayout.WEST);
        JComboBox<String> kindSelector = new JComboBox<>(options);
        kindSelector.setSelectedItem(options[0]);
        for (String option : options) {
            if (option.equalsIgnoreCase(current)) {
                kindSelector.setSelectedItem(option);
                break;
            }
        }
        form.add(kindSelector, BorderLayout.CENTER);

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("Cancel");
        JButton okBtn = new JButton("OK");
        buttonRow.add(cancelBtn);
        buttonRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            Object selected = kindSelector.getSelectedItem();
            result[0] = selected != null ? selected.toString() : null;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        if (!committed[0]) return null;
        return result[0];
    }

    private void startEditingImpactLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.IMPACT && sel.tool != Tool.ARROW_OPEN 
            && sel.tool != Tool.ARROW_EMPTY && sel.tool != Tool.ARROW_DIAMOND) return;
        if (isTransitionTool(sel.tool)) {
            beginLabelEdit(index);
            String newText = showTransitionEditorDialog(sel);
            endLabelEdit();
            if (newText == null) return;
            updateShapeLabel(newText, index);
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
            return;
        }
        beginLabelEdit(index);
        String newText = showImpactEditorDialog(sel);
        endLabelEdit();
        if (newText == null) return;
        updateShapeLabel(newText, index);
        DrawingCanvas.this.revalidate();
        DrawingCanvas.this.repaint();
    }

    private void startEditingReferenceLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.REFERENCE) return;
        beginLabelEdit(index);

        String newText = showReferenceEditorDialog(sel);
        endLabelEdit();
        if (newText == null) return;
        updateShapeLabel(newText, index);
        DrawingCanvas.this.revalidate();
        DrawingCanvas.this.repaint();
    }

    private double angleDistance(double a, double b) {
        double diff = Math.abs(a - b) % (Math.PI * 2.0);
        return Math.min(diff, Math.PI * 2.0 - diff);
    }

    private String parallelConnectorGroupKey(ShapeRecord r) {
        if (r == null || !isConnectorTool(r.tool)) return null;
        if (r.anchorFromId == null || r.anchorToId == null) return null;
        if (r.anchorFromId.equals(r.anchorToId)) return null;
        String from = r.anchorFromId;
        String to = r.anchorToId;
        if (from.compareTo(to) > 0) {
            String tmp = from;
            from = to;
            to = tmp;
        }
        return from + "|" + to;
    }

    private int parallelConnectorCount(ShapeRecord target) {
        String key = parallelConnectorGroupKey(target);
        if (key == null) return 0;
        int count = 0;
        for (ShapeRecord r : shapes) {
            if (r == null) continue;
            if (key.equals(parallelConnectorGroupKey(r))) count++;
        }
        return count;
    }

    private int parallelConnectorIndex(ShapeRecord target) {
        String key = parallelConnectorGroupKey(target);
        if (key == null) return 0;
        int index = 0;
        for (ShapeRecord r : shapes) {
            if (r == null) continue;
            if (!key.equals(parallelConnectorGroupKey(r))) continue;
            if (r == target) return index;
            index++;
        }
        return 0;
    }

    private double getParallelConnectorCurveOffset(ShapeRecord r) {
        int count = parallelConnectorCount(r);
        if (count < 2) return 0.0;
        double centered = parallelConnectorIndex(r) - ((count - 1) / 2.0);


        double directionPolarity = 1.0;
        if (r != null && r.anchorFromId != null && r.anchorToId != null) {
            directionPolarity = r.anchorFromId.compareTo(r.anchorToId) <= 0 ? 1.0 : -1.0;
        }
        return centered * PARALLEL_CONNECTOR_CURVE_SPACING * directionPolarity;
    }

    /**
     * Draws a parallel connector as a quadratic curve and trims the shaft before the head.
     */
    private void drawCurvedConnector(Graphics2D g, ShapeRecord r, double curveOffset) {
        Point2D.Double start = new Point2D.Double(r.x1, r.y1);
        Point2D.Double end = new Point2D.Double(r.x2, r.y2);
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            g.draw(new Line2D.Double(start.x, start.y, end.x, end.y));
            return;
        }

        double ux = dx / len;
        double uy = dy / len;
        double px = -uy;
        double py = ux;
        


        double endpointOffset = curveOffset * 0.3;  // 30% of curve offset
        double startOffsetX = start.x + px * endpointOffset;
        double startOffsetY = start.y + py * endpointOffset;
        double endOffsetX = end.x + px * endpointOffset;
        double endOffsetY = end.y + py * endpointOffset;
        
        double ctrlX = (startOffsetX + endOffsetX) / 2.0 + px * curveOffset * 0.7;
        double ctrlY = (startOffsetY + endOffsetY) / 2.0 + py * curveOffset * 0.7;
        double headLen = Math.max(8, 6 + strokeWidth * 2);
        double tailDx = endOffsetX - ctrlX;
        double tailDy = endOffsetY - ctrlY;
        double tailLen = Math.hypot(tailDx, tailDy);
        double tailUx = tailLen < 1e-6 ? ux : tailDx / tailLen;
        double tailUy = tailLen < 1e-6 ? uy : tailDy / tailLen;


        double curveEndX = endOffsetX - tailUx * headLen;
        double curveEndY = endOffsetY - tailUy * headLen;


        java.awt.geom.QuadCurve2D curve = new java.awt.geom.QuadCurve2D.Double(
            startOffsetX, startOffsetY, ctrlX, ctrlY, curveEndX, curveEndY);
        g.draw(curve);
        drawArrowHead(g, ctrlX, ctrlY, endOffsetX, endOffsetY, r.tool);
    }

    private void drawConnectorRecords(Graphics2D g) {
        for (ShapeRecord r : shapes) {
            if (r == null || !isConnectorTool(r.tool)) continue;
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


        double rx = Math.max(8.0, Math.abs(start.x - end.x));
        double ry = Math.max(8.0, Math.abs(end.y - start.y));


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

        if (r.tool == Tool.TASK) {

            double rx = b.getWidth() / 2.0;
            double ry = b.getHeight() / 2.0;
            if (rx <= 0 || ry <= 0) return new Point2D.Double(from.x, from.y);
            double t = 1.0 / Math.sqrt((dx * dx) / (rx * rx) + (dy * dy) / (ry * ry));
            return new Point2D.Double(from.x + dx * t, from.y + dy * t);
        }


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

        Object ox1 = e.get("x1"); Object oy1 = e.get("y1"); Object ox2 = e.get("x2"); Object oy2 = e.get("y2");
        int x1 = ox1 instanceof Number ? ((Number)ox1).intValue() : 10;
        int y1 = oy1 instanceof Number ? ((Number)oy1).intValue() : 10;
        int x2 = ox2 instanceof Number ? ((Number)ox2).intValue() : x1 + 80;
        int y2 = oy2 instanceof Number ? ((Number)oy2).intValue() : y1 + 40;

        String shapeTypeStr = e.get("shapeType") instanceof String ? (String)e.get("shapeType") : null;
        Tool t = Tool.OBJECT;
        if (shapeTypeStr != null) {
            try {
                t = Tool.valueOf(shapeTypeStr.toUpperCase());
            } catch (Exception ex) {

            }
        }

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
            case PROCESS:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                break;
            case TASK:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                break;
            case ACTION:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                break;
            case ACTOR:
                s = buildActorShape(rx, ry, rw, rh);
                break;
            case OBJECT:
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

        if (r.tool != null) ent.put("shapeType", r.tool.name());
        if (r.color != null) ent.put("colorRGB", r.color.getRGB());
        if (r.text != null) ent.put("text", r.text);
        ent.put("strokeWidth", r.stroke);
        return ent;
    }


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

    private class GroupMoveEdit extends AbstractUndoableEdit {
        final int[] indices;
        private final ShapeRecord[] before;
        private ShapeRecord[] after;

        GroupMoveEdit(int[] indices, ShapeRecord[] before) {
            this.indices = indices == null ? new int[0] : indices.clone();
            this.before = before == null ? new ShapeRecord[0] : before.clone();
        }

        void setAfterRecords(ShapeRecord[] after) {
            this.after = after == null ? new ShapeRecord[0] : after.clone();
        }

        @Override
        public void undo() {
            super.undo();
            for (int i = 0; i < indices.length; i++) {
                int idx = indices[i];
                if (idx >= 0 && idx < shapes.size() && i < before.length) {
                    shapes.set(idx, copyShapeRecord(before[i]));
                }
            }
            redrawBuffer();
            repaint();
        }

        @Override
        public void redo() {
            super.redo();
            for (int i = 0; i < indices.length; i++) {
                int idx = indices[i];
                if (idx >= 0 && idx < shapes.size() && after != null && i < after.length) {
                    shapes.set(idx, copyShapeRecord(after[i]));
                }
            }
            redrawBuffer();
            repaint();
        }

        @Override
        public String getPresentationName() { return "Move Group"; }
    }


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


    public enum Tool {
        SELECT, PAN, DELETE, FREEHAND, 
        LINE,                   // General association
        ARROW_FILLED,           // Dataflow (Task Model)
        ARROW_EMPTY,            // Generalisation
        ARROW_DIAMOND,          // Composition
        ARROW_OPEN,             // Event/Transition
        IMPACT, REFERENCE, ENACTS,
        TASK, OBJECT, PROCESS, ACTION, BOUNDARY, OBJECT_TYPE,
        TEXT, STATE, ACTOR, SYSTEM, AUTHORISATION, 
        INITIAL_TRANSITION, FINAL_TRANSITION
    }
    private Tool currentTool = Tool.SELECT;


    private final java.util.List<ShapeRecord> shapes = new ArrayList<>();
    private ShapeRecord preview = null;


    private PendingConnector pendingConnector = null;


    private ReMoDeLModel model = null;
    private final java.util.Map<String, Integer> idToIndex = new java.util.HashMap<>();


    public ReMoDeLModel getModel() {
        return model;
    }

    public void setDocumentModelTypeName(String modelTypeName) {
        if (modelTypeName == null || modelTypeName.isBlank()) return;
        documentModelTypeName = modelTypeName.trim();
    }

    public String getLoadedDocumentModelTypeName() {
        return loadedDocumentModelTypeName;
    }


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

    public void setImpactLabel(String label) {
        impactLabel = label != null && !label.isBlank() ? label.trim() : "create";
    }

    public void setDataflowKind(DataflowKind kind) {
        dataflowKind = kind != null ? kind : DataflowKind.OBJECT;
    }

    public void setProcessActionKind(ProcessActionKind kind) {
        processActionKind = kind != null ? kind : ProcessActionKind.INPUT;
    }


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
    private static final double PARALLEL_CONNECTOR_CURVE_SPACING = 100.0;
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
    private static final int PROCESS_DEFAULT_WIDTH = 600;
    private static final int PROCESS_DEFAULT_HEIGHT = 420;
    private static final int PROCESS_CLICK_THRESHOLD = 18;
    private static final String DEFAULT_TEXT_FONT_NAME = "SansSerif";
    private static final int DEFAULT_TEXT_FONT_SIZE = 25;
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

    private void rebuildObjectTypeAttributeRows(JPanel attributesPanel, java.util.List<JTextField> attributeFields, java.util.List<JCheckBox> underlineChecks, JDialog dialog) {
        attributesPanel.removeAll();
        for (int i = 0; i < attributeFields.size(); i++) {
            JTextField field = attributeFields.get(i);
            JCheckBox underline = underlineChecks.size() > i ? underlineChecks.get(i) : new JCheckBox("_");
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.add(new JLabel((i + 1) + "."), BorderLayout.WEST);
            JPanel center = new JPanel(new BorderLayout(6, 0));
            center.add(field, BorderLayout.CENTER);
            underline.setToolTipText("Underline attribute");
            underline.setFocusable(false);
            center.add(underline, BorderLayout.EAST);
            row.add(center, BorderLayout.CENTER);
            JButton removeBtn = new JButton("-");
            removeBtn.setFocusable(false);
            final int removeIndex = i;
            removeBtn.addActionListener(e -> {
                if (attributeFields.size() <= 1) return;
                attributeFields.remove(removeIndex);
                underlineChecks.remove(removeIndex);
                rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, underlineChecks, dialog);
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
        java.util.List<JCheckBox> underlineChecks = new ArrayList<>();

        for (String attribute : initial.attributes) {
            boolean under = false;
            String a = attribute != null ? attribute : "";
            if (a.startsWith("*")) {
                under = true;
                a = a.substring(1).trim();
            }
            attributeFields.add(new JTextField(a, 24));
            underlineChecks.add(new JCheckBox("_", under));
        }
        rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, underlineChecks, dialog);

        JScrollPane scrollPane = new JScrollPane(attributesPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Attributes"));
        scrollPane.setPreferredSize(new Dimension(420, 220));
        form.add(scrollPane);

        JPanel actionRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton addBtn = new JButton("Add Attribute");
        addBtn.addActionListener(e -> {
            attributeFields.add(new JTextField("", 24));
            underlineChecks.add(new JCheckBox("_", false));
            rebuildObjectTypeAttributeRows(attributesPanel, attributeFields, underlineChecks, dialog);
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
            for (int i = 0; i < attributeFields.size(); i++) {
                JTextField field = attributeFields.get(i);
                String value = field.getText() != null ? field.getText().trim() : "";
                if (value.isEmpty()) continue;
                JCheckBox cb = underlineChecks.size() > i ? underlineChecks.get(i) : null;
                if (cb != null && cb.isSelected()) {
                    attributes.add("*" + value);
                } else {
                    attributes.add(value);
                }
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


    private boolean isConstrainedSizeTool(Tool t) {
        return t == Tool.OBJECT
            || t == Tool.TASK
            || t == Tool.ACTION
            || t == Tool.STATE
            || t == Tool.OBJECT_TYPE
            || t == Tool.SYSTEM
            || t == Tool.ACTOR;
    }


    private int[] enforceDefaultSize(Tool tool, int width, int height) {
        if (!isConstrainedSizeTool(tool)) return new int[] { width, height };
        if (tool == Tool.PROCESS) {
            return new int[] { PROCESS_DEFAULT_WIDTH, PROCESS_DEFAULT_HEIGHT };
        }
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

        final String entityId; // optional associated model entity id (if this shape is backed by ReMoDeLModel)
        final String localId; // stable id for non-model shapes/connectors
        final String anchorFromId; // connector source local id
        final String anchorToId; // connector target local id
        final double x1, y1, x2, y2;

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


        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, null, null);
        }


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
        private final double zoomScale;
        private final String modelTypeName;

        CanvasSnapshot(java.util.List<ShapeRecord> shapes, double zoomScale, String modelTypeName) {
            this.shapes = shapes;
            this.zoomScale = zoomScale;
            this.modelTypeName = modelTypeName;
        }
    }


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

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newBuf.getWidth(), newBuf.getHeight());

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

            private int[] computePanGroup(int startIndex) {
                if (startIndex < 0 || startIndex >= shapes.size()) return new int[0];
                java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
                set.add(startIndex);

                boolean changed = true;
                while (changed) {
                    changed = false;

                    for (int i = 0; i < shapes.size(); i++) {
                        ShapeRecord r = shapes.get(i);
                        if (r == null || !isConnectorTool(r.tool)) continue;
                        String a = r.anchorFromId;
                        String b = r.anchorToId;
                        if (a == null || b == null) continue;
                        ShapeRecord from = findShapeByLocalId(a);
                        ShapeRecord to = findShapeByLocalId(b);
                        int fromIdx = from != null ? shapes.indexOf(from) : -1;
                        int toIdx = to != null ? shapes.indexOf(to) : -1;
                        if (fromIdx >= 0 && toIdx >= 0) {
                            if (set.contains(fromIdx) && !set.contains(toIdx)) { set.add(toIdx); changed = true; }
                            if (set.contains(toIdx) && !set.contains(fromIdx)) { set.add(fromIdx); changed = true; }
                        }
                    }

                    for (int i = 0; i < shapes.size(); i++) {
                        ShapeRecord container = shapes.get(i);
                        if (container == null) continue;
                        if (!(container.tool == Tool.PROCESS || container.tool == Tool.BOUNDARY)) continue;
                        Rectangle2D cb = container.shape.getBounds2D();
                        for (int j = 0; j < shapes.size(); j++) {
                            if (i == j) continue;
                            ShapeRecord child = shapes.get(j);
                            if (child == null || isConnectorTool(child.tool)) continue;
                            Point2D center = getShapeCenter(child);
                            if (center != null && cb.contains(center)) {
                                if (set.contains(i) && !set.contains(j)) { set.add(j); changed = true; }
                                if (set.contains(j) && !set.contains(i)) { set.add(i); changed = true; }
                            }
                        }
                    }
                }

                int[] out = new int[set.size()];
                int p = 0;
                for (Integer v : set) out[p++] = v;
                return out;
            }

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
                            
                            int[] group = computePanGroup(selectedIndex);
                            if (group.length > 1) {
                                currentPanGroup = group;
                                ShapeRecord[] befores = new ShapeRecord[group.length];
                                for (int i = 0; i < group.length; i++) befores[i] = copyShapeRecord(shapes.get(group[i]));
                                currentMoveEdit = new GroupMoveEdit(group, befores);
                            } else {
                                selectedIndex = hit;
                                ShapeRecord before = copyShapeRecord(selected);
                                currentMoveEdit = new MoveEdit(selectedIndex, before);
                            }
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

                    preview = null;

                    int hit = hitTest(lastX, lastY);
                    if (hit >= 0) {

                        if (e.getClickCount() == 2) {
                            ShapeRecord sr = shapes.get(hit);

                            if (sr.tool == Tool.TEXT) {
                                startEditingText(hit);
                                return;
                            } else if (sr.tool == Tool.ACTION) {
                                startEditingProcessActionLabel(hit);
                                return;
                            }

                                else if (sr.tool == Tool.OBJECT || sr.tool == Tool.TASK || sr.tool == Tool.PROCESS ||
                                    sr.tool == Tool.STATE || sr.tool == Tool.ACTOR || sr.tool == Tool.SYSTEM
                                    || sr.tool == Tool.BOUNDARY) {
                                startEditingShapeLabel(hit);
                                return;
                            } else if (sr.tool == Tool.OBJECT_TYPE) {
                                startEditingObjectTypeLabel(hit);
                                return;
                            } else if (sr.tool == Tool.ARROW_FILLED) {
                                startEditingDataflowLabel(hit);
                                return;
                            } else if (sr.tool == Tool.IMPACT || sr.tool == Tool.ARROW_OPEN 
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

                        if (isConnectorTool(selected.tool)) {
                            activeConnectorEndpoint = connectorHandleHit(selected, lastX, lastY);
                            if (activeConnectorEndpoint >= 0) {
                                draggingConnectorEndpoint = true;
                            } else {
                                draggingMove = true;
                            }
                        } else {
                            if (isConstrainedSizeTool(selected.tool) && selected.tool != Tool.PROCESS) {
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

                        ShapeRecord before = copyShapeRecord(shapes.get(selectedIndex));
                        currentMoveEdit = new MoveEdit(selectedIndex, before);
                        repaint();
                    } else {

                        selectedIndex = -1;
                        repaint();
                    }
                }


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
                        int dx = x - lastX, dy = y - lastY;
                        if (currentPanGroup != null && currentPanGroup.length > 1 && currentMoveEdit instanceof GroupMoveEdit) {
                            GroupMoveEdit gme = (GroupMoveEdit) currentMoveEdit;
                            ShapeRecord[] afters = new ShapeRecord[currentPanGroup.length];
                            for (int i = 0; i < currentPanGroup.length; i++) {
                                int idx = currentPanGroup[i];
                                ShapeRecord sel = shapes.get(idx);
                                if (sel == null) continue;
                                if (sel.tool == Tool.TEXT) {
                                    double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                    Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                    ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font,
                                            sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                    nr = clampRecordWithinCanvas(nr);
                                    shapes.set(idx, nr);
                                    afters[i] = copyShapeRecord(nr);
                                } else {
                                    Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                    ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                            sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, sel.text, sel.font,
                                            sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                    nr = clampRecordWithinCanvas(nr);
                                    shapes.set(idx, nr);
                                    afters[i] = copyShapeRecord(nr);
                                }
                            }
                            
                            for (int i = 0; i < currentPanGroup.length; i++) {
                                ShapeRecord r = shapes.get(currentPanGroup[i]);
                                if (r != null && r.localId != null) reanchorConnectorsFor(r.localId);
                            }
                            gme.setAfterRecords(afters);
                        } else {
                            ShapeRecord sel = shapes.get(selectedIndex);
                            if (!isConnectorTool(sel.tool)) {
                                if (sel.tool == Tool.TEXT) {
                                    double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                    Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                    ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font,
                                            sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                    nr = clampRecordWithinCanvas(nr);
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                } else {
                                    Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                    ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                            sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, sel.text, sel.font,
                                            sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                    nr = clampRecordWithinCanvas(nr);
                                    shapes.set(selectedIndex, nr);
                                    reanchorConnectorsFor(nr.localId);
                                }
                            }
                        }
                        redrawBuffer();
                        repaint();
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

                                double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                nr = clampRecordWithinCanvas(nr);
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
                                nr = clampRecordWithinCanvas(nr);
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

                                ShapeRecord nr = new ShapeRecord(Tool.TEXT,
                                        new Rectangle2D.Double(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1)),
                                        sel.color, sel.stroke, x1, y1, x2, y2, sel.text, sel.font,
                                        sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                nr = clampRecordWithinCanvas(nr);
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
                                    nr = clampRecordWithinCanvas(nr);
                                    String keptText = sel.text != null ? sel.text : nr.text;
                                    Font keptFont = normalizeTextFont(sel.font != null ? sel.font : nr.font);
                                    ShapeRecord withId = new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2,
                                            keptText, keptFont, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
                                    shapes.set(selectedIndex, withId);
                                    reanchorConnectorsFor(withId.localId);
                                    if (sel.entityId != null && model != null) {
                                        model.updateEntity(entityFromShape(withId));
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

                    draggingMove = false;
                    resizing = false;
                    draggingConnectorEndpoint = false;
                    activeHandle = -1;
                    activeConnectorEndpoint = -1;

                    if (currentMoveEdit != null) {
                        if (currentMoveEdit instanceof MoveEdit) {
                            if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
                                ShapeRecord afterRec = copyShapeRecord(shapes.get(selectedIndex));
                                ((MoveEdit) currentMoveEdit).setAfter(afterRec);
                                if (model != null && afterRec != null && afterRec.entityId != null) {
                                    ReMoDeLEntity ent = entityFromShape(afterRec);
                                    model.updateEntity(ent);
                                }
                            }
                        } else if (currentMoveEdit instanceof GroupMoveEdit) {
                            GroupMoveEdit g = (GroupMoveEdit) currentMoveEdit;
                            if (g.indices != null && g.indices.length > 0) {
                                ShapeRecord[] afters = new ShapeRecord[g.indices.length];
                                for (int i = 0; i < g.indices.length; i++) {
                                    int idx = g.indices[i];
                                    if (idx >= 0 && idx < shapes.size()) afters[i] = copyShapeRecord(shapes.get(idx));
                                }
                                g.setAfterRecords(afters);
                                
                                if (model != null) {
                                    for (int i = 0; i < g.indices.length; i++) {
                                        ShapeRecord r = afters[i];
                                        if (r != null && r.entityId != null) {
                                            ReMoDeLEntity ent = entityFromShape(r);
                                            model.updateEntity(ent);
                                        }
                                    }
                                }
                            }
                        }
                        addUndoableEdit(currentMoveEdit);
                        currentMoveEdit = null;
                        currentPanGroup = null;
                    }
                    if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
                        ensureShapeVisible(shapes.get(selectedIndex));
                    }
                    redrawBuffer();
                    repaint();
                    statusConsumer.accept("Ready");
                    if (currentTool == Tool.PAN) {
                        setCurrentTool(Tool.SELECT);
                    }
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
                        

                        int newIndex = shapes.size() - 1;
                        if (newIndex >= 0 && !isConnectorTool(drawnTool)) {

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
                if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
                    ensureShapeVisible(shapes.get(selectedIndex));
                }
                repaint();
            }
        });
    }


    private void startLabelEditingForDrawnShape(int index, Tool tool) {
        if (index < 0 || index >= shapes.size()) return;
        

        if (tool == Tool.TEXT) {
            startEditingText(index);
        } else if (tool == Tool.ACTION) {
            startEditingProcessActionLabel(index);
        } else if (tool == Tool.OBJECT || tool == Tool.TASK ||
                   tool == Tool.PROCESS || tool == Tool.STATE ||
                   tool == Tool.ACTOR || tool == Tool.SYSTEM ||
                   tool == Tool.BOUNDARY) {
            startEditingShapeLabel(index);
        } else if (tool == Tool.OBJECT_TYPE) {
            startEditingObjectTypeLabel(index);
        } else if (tool == Tool.IMPACT || tool == Tool.ARROW_OPEN 
                   || tool == Tool.ARROW_EMPTY || tool == Tool.ARROW_DIAMOND 
                   || tool == Tool.INITIAL_TRANSITION || tool == Tool.FINAL_TRANSITION) {
            startEditingImpactLabel(index);
        } else if (tool == Tool.ARROW_FILLED) {
            startEditingDataflowLabel(index);
        } else if (tool == Tool.REFERENCE) {
            startEditingReferenceLabel(index);
        }
    }

    private void startEditingShapeLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);

        if (sel.tool != Tool.OBJECT && sel.tool != Tool.TASK &&
            sel.tool != Tool.PROCESS && sel.tool != Tool.STATE &&
            sel.tool != Tool.ACTOR && sel.tool != Tool.SYSTEM &&
            sel.tool != Tool.BOUNDARY) {
            return;
        }
        
        Rectangle2D b = getShapeBounds(sel);
        if (b == null) return;
        beginLabelEdit(index);


        String currentText = sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool);
        
        final JTextField tf = new JTextField(currentText);
        tf.setOpaque(false);
        tf.setBackground(new Color(0, 0, 0, 0));
        tf.setForeground(sel.color != null ? sel.color : drawColor);
        tf.setFont(zoomAwareEditorFont(sel.font));
        tf.setHorizontalAlignment(sel.tool == Tool.PROCESS || sel.tool == Tool.BOUNDARY ? JTextField.LEFT : JTextField.CENTER);
        styleInlineEditor(tf);


        switch (sel.tool) {
            case STATE:

                int radius = (int) b.getHeight();
                tf.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(radius),
                    BorderFactory.createEmptyBorder(3, 10, 3, 10) // padding
                ));
                break;
            case TASK:

                tf.setBorder(BorderFactory.createCompoundBorder(
                    new EllipseBorder(),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
                break;
            case PROCESS:

                tf.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(15),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)
                ));
                break;
            case BOUNDARY:

                tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(null, 0),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)
                ));
                break;
            case OBJECT:
            default:

                tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(null, 0),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
                break;
        }


        FontMetrics fm = tf.getFontMetrics(tf.getFont());
        int textWidth = fm.stringWidth(currentText);
        int textHeight = fm.getHeight();
        int editorWidth = Math.max(260, textWidth + 20);  
        int editorHeight = Math.max(20, textHeight + 6);
        
        if (sel.tool == Tool.BOUNDARY) {

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
        } else if (sel.tool == Tool.PROCESS) {
            tf.setBounds(zoomedBounds(
                b.getX() + 8,
                b.getY() + 4,
                Math.min(editorWidth, Math.max(80, (int) Math.round(b.getWidth() - 16))),
                editorHeight
            ));
        } else if (sel.tool == Tool.ACTOR) {

            tf.setBounds(zoomedBounds(
                b.getCenterX() - editorWidth / 2.0,
                b.getY() + b.getHeight() + 4,
                editorWidth,
                editorHeight
            ));
        } else {

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

    private void startEditingDataflowLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.ARROW_FILLED) return;
        beginLabelEdit(index);
        String newText = showDataflowEditorDialog(sel);
        endLabelEdit();
        if (newText == null) return;
        updateShapeLabel(newText, index);
        DrawingCanvas.this.revalidate();
        DrawingCanvas.this.repaint();
    }

    private void startEditingProcessActionLabel(int index) {
        if (index < 0 || index >= shapes.size()) return;
        ShapeRecord sel = shapes.get(index);
        if (sel.tool != Tool.ACTION) return;
        beginLabelEdit(index);
        String newText = showProcessActionEditorDialog(sel);
        endLabelEdit();
        if (newText == null) return;
        updateShapeLabel(newText, index);
        DrawingCanvas.this.revalidate();
        DrawingCanvas.this.repaint();
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


    class EllipseBorder implements javax.swing.border.Border {
        
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(100, 150, 255)); // Blue outline
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


    private String getDefaultLabelForTool(Tool tool) {
        switch (tool) {
            case OBJECT: return "Object";
            case OBJECT_TYPE: return "Object\nattribute";
            case ACTOR: return "Actor";
            case SYSTEM: return "System";
            case TASK: return "Task";
            case PROCESS: return "Process";
            case ACTION: return processActionKind != null ? processActionKind.displayLabel() : ProcessActionKind.INPUT.displayLabel();
            case STATE: return "State";
            case LINE: return "Association";
            case AUTHORISATION: return "Authorisation";
            case ARROW_FILLED: return dataflowKind != null ? dataflowKind.defaultLabel() : DataflowKind.OBJECT.defaultLabel();
            case ARROW_OPEN: return "event";
            case INITIAL_TRANSITION: return "enter";
            case FINAL_TRANSITION: return "exit";
            case IMPACT: return impactLabel != null && !impactLabel.isBlank() ? impactLabel : "create";
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

    public enum DataflowKind {
        OBJECT("Object Dataflow", "Object"),
        CONTENT("Content Dataflow", "Object {val}"),
        IDENTITY("Identity Dataflow", "Object {id}"),
        GENERAL_OBJECT("General Object Dataflow", "Object (Subtype)");

        private final String label;
        private final String defaultLabel;

        DataflowKind(String label, String defaultLabel) {
            this.label = label;
            this.defaultLabel = defaultLabel;
        }

        public String defaultLabel() {
            return defaultLabel;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ProcessActionKind {
        INPUT("InputAction", "Input"),
        OUTPUT("OutputAction", "Output"),
        FETCH("FetchAction", "Fetch"),
        STORE("StoreAction", "Store"),
        CREATE("CreateAction", "Create"),
        UPDATE("UpdateAction", "Update"),
        DELETE("DeleteAction", "Delete");

        private final String displayLabel;
        private final String dslKind;

        ProcessActionKind(String displayLabel, String dslKind) {
            this.displayLabel = displayLabel;
            this.dslKind = dslKind;
        }

        public String displayLabel() {
            return dslKind;
        }

        @Override
        public String toString() {
            return displayLabel;
        }
    }

    private static class DataflowEditParts {
        final DataflowKind kind;
        final String name;
        final String subtypes;
        final String annotation;

        DataflowEditParts(DataflowKind kind, String name, String subtypes, String annotation) {
            this.kind = kind;
            this.name = name;
            this.subtypes = subtypes;
            this.annotation = annotation;
        }
    }

    private static class ProcessActionEditParts {
        final ProcessActionKind kind;
        final String actionWord;
        final String description;

        ProcessActionEditParts(ProcessActionKind kind, String actionWord, String description) {
            this.kind = kind;
            this.actionWord = actionWord;
            this.description = description;
        }
    }

    private DataflowEditParts parseDataflowParts(String text) {
        String raw = text != null ? text.trim() : "";
        if (raw.isBlank()) {
            return new DataflowEditParts(DataflowKind.OBJECT, "Object", "", "");
        }

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

        DataflowKind kind;
        if (!subtypes.isEmpty()) {
            kind = DataflowKind.GENERAL_OBJECT;
        } else if ("{val}".equals(annotation)) {
            kind = DataflowKind.CONTENT;
        } else if ("{id}".equals(annotation)) {
            kind = DataflowKind.IDENTITY;
        } else {
            kind = DataflowKind.OBJECT;
        }

        if (raw.isBlank()) raw = "Object";
        return new DataflowEditParts(kind, raw, subtypes, annotation);
    }

    private String buildDataflowLabel(String name, String subtypes, DataflowKind kind, String annotation) {
        String safeName = name != null && !name.isBlank() ? name.trim() : "Object";
        String safeSubtypes = subtypes != null ? subtypes.trim() : "";
        String safeAnnotation = annotation != null ? annotation.trim() : "";

        switch (kind != null ? kind : DataflowKind.OBJECT) {
            case CONTENT:
                return safeName + " {val}";
            case IDENTITY:
                return safeName + " {id}";
            case GENERAL_OBJECT:
                StringBuilder out = new StringBuilder(safeName);
                if (!safeSubtypes.isEmpty()) {
                    out.append(" (").append(safeSubtypes).append(')');
                }
                if (!safeAnnotation.isEmpty()) {
                    out.append(' ').append(safeAnnotation);
                }
                return out.toString();
            case OBJECT:
            default:
                return safeName;
        }
    }

    private ProcessActionEditParts parseProcessActionParts(String text) {
        String raw = text != null ? text.trim() : "";
        if (raw.isBlank()) {
            String fallback = processActionKind != null ? processActionKind.displayLabel() : ProcessActionKind.INPUT.displayLabel();
            return new ProcessActionEditParts(processActionKind != null ? processActionKind : ProcessActionKind.INPUT, fallback, "");
        }

        for (ProcessActionKind kind : ProcessActionKind.values()) {
            String label = kind.displayLabel();
            if (raw.equalsIgnoreCase(label)) {
                return new ProcessActionEditParts(kind, label, "");
            }
            if (raw.regionMatches(true, 0, label + "", 0, label.length() + 1)) {
                String description = raw.substring(label.length() + 1).trim();
                return new ProcessActionEditParts(kind, label, description);
            }
        }

        int colon = raw.indexOf(':');
        if (colon > 0) {
            String left = raw.substring(0, colon).trim();
            String right = raw.substring(colon + 1).trim();
            if (!left.isBlank()) {
                ProcessActionKind matched = null;
                for (ProcessActionKind kind : ProcessActionKind.values()) {
                    if (kind.displayLabel().equalsIgnoreCase(left) || kind.toString().equalsIgnoreCase(left)) {
                        matched = kind;
                        left = kind.displayLabel();
                        break;
                    }
                }
                return new ProcessActionEditParts(matched, left, right);
            }
        }

        return new ProcessActionEditParts(null, raw, "");
    }

    private String buildProcessActionLabel(String actionWord, String description) {
        String safeActionWord = actionWord != null && !actionWord.isBlank()
            ? actionWord.trim()
            : (processActionKind != null ? processActionKind.displayLabel() : ProcessActionKind.INPUT.displayLabel());
        String safeDescription = description != null ? description.trim() : "";
        if (safeDescription.isEmpty()) {
            return safeActionWord;
        }
        return safeActionWord + " " + safeDescription;
    }

    private String showProcessActionEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Action", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Action", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        ProcessActionEditParts initial = parseProcessActionParts(sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel kindRow = new JPanel(new BorderLayout(8, 0));
        kindRow.add(new JLabel("Action:"), BorderLayout.WEST);
        JComboBox<ProcessActionKind> kindSelector = new JComboBox<>(ProcessActionKind.values());
        kindSelector.setSelectedItem(initial.kind != null ? initial.kind : processActionKind != null ? processActionKind : ProcessActionKind.INPUT);
        kindRow.add(kindSelector, BorderLayout.CENTER);
        form.add(kindRow);
        form.add(Box.createVerticalStrut(10));

        JPanel descriptionRow = new JPanel(new BorderLayout(8, 0));
        descriptionRow.add(new JLabel("Description:"), BorderLayout.WEST);
        JTextField descriptionField = new JTextField(initial.description, 28);
        descriptionField.setEditable(true);
        descriptionRow.add(descriptionField, BorderLayout.CENTER);
        form.add(descriptionRow);

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("Cancel");
        JButton okBtn = new JButton("OK");
        buttonRow.add(cancelBtn);
        buttonRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            ProcessActionKind selected = (ProcessActionKind) kindSelector.getSelectedItem();
            String actionWord = selected != null ? selected.displayLabel() : "";
            result[0] = buildProcessActionLabel(actionWord, descriptionField.getText());
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        descriptionField.selectAll();
        dialog.setVisible(true);

        if (!committed[0]) return null;
        return result[0];
    }

    private String showDataflowEditorDialog(ShapeRecord sel) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = owner != null
            ? new JDialog(owner, "Edit Dataflow", Dialog.ModalityType.APPLICATION_MODAL)
            : new JDialog((java.awt.Frame) null, "Edit Dataflow", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        DataflowEditParts initial = parseDataflowParts(sel.text != null ? sel.text : getDefaultLabelForTool(sel.tool));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel kindRow = new JPanel(new BorderLayout(8, 0));
        kindRow.add(new JLabel("Dataflow kind:"), BorderLayout.WEST);
        JComboBox<DataflowKind> kindSelector = new JComboBox<>(DataflowKind.values());
        kindSelector.setSelectedItem(initial.kind);
        kindRow.add(kindSelector, BorderLayout.CENTER);
        form.add(kindRow);
        form.add(Box.createVerticalStrut(10));

        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.add(new JLabel("Object name:"), BorderLayout.WEST);
        JTextField nameField = new JTextField(initial.name, 26);
        nameRow.add(nameField, BorderLayout.CENTER);
        form.add(nameRow);
        form.add(Box.createVerticalStrut(10));

        JPanel subtypeRow = new JPanel(new BorderLayout(8, 0));
        subtypeRow.add(new JLabel("Concrete subtypes:"), BorderLayout.WEST);
        JTextField subtypeField = new JTextField(initial.subtypes, 26);
        subtypeRow.add(subtypeField, BorderLayout.CENTER);
        form.add(subtypeRow);
        form.add(Box.createVerticalStrut(10));

        JPanel annotationRow = new JPanel(new BorderLayout(8, 0));
        annotationRow.add(new JLabel("Annotation:"), BorderLayout.WEST);
        JComboBox<String> annotationSelector = new JComboBox<>(new String[] { "", "{id}", "{val}" });
        annotationSelector.setSelectedItem(initial.annotation);
        annotationRow.add(annotationSelector, BorderLayout.CENTER);
        form.add(annotationRow);

        Runnable syncFields = () -> {
            DataflowKind selected = (DataflowKind) kindSelector.getSelectedItem();
            boolean general = selected == DataflowKind.GENERAL_OBJECT;
            subtypeField.setEnabled(general);
            annotationSelector.setEnabled(general);
            if (!general) {
                if (selected == DataflowKind.CONTENT) {
                    annotationSelector.setSelectedItem("{val}");
                } else if (selected == DataflowKind.IDENTITY) {
                    annotationSelector.setSelectedItem("{id}");
                } else {
                    annotationSelector.setSelectedItem("");
                }
            }
        };
        kindSelector.addActionListener(e -> syncFields.run());
        syncFields.run();

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("Cancel");
        JButton okBtn = new JButton("OK");
        buttonRow.add(cancelBtn);
        buttonRow.add(okBtn);

        root.add(form, BorderLayout.CENTER);
        root.add(buttonRow, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        final boolean[] committed = { false };
        final String[] result = { null };
        okBtn.addActionListener(e -> {
            committed[0] = true;
            DataflowKind selectedKind = (DataflowKind) kindSelector.getSelectedItem();
            String name = nameField.getText();
            String subtypes = subtypeField.getText();
            String annotation = (String) annotationSelector.getSelectedItem();
            result[0] = buildDataflowLabel(name, subtypes, selectedKind, annotation);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        nameField.selectAll();
        dialog.setVisible(true);

        if (!committed[0]) return null;
        return result[0];
    }


    public java.util.List<String> validateCompleteness(com.example.swingapp.persistence.ReMoDeLExporter.ModelKind modelKind) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        int countTask = 0;
        int countState = 0;
        int countTransition = 0;
        int countObject = 0;
        int countObjectType = 0;

        for (ShapeRecord r : shapes) {
            if (r == null) continue;
            switch (r.tool) {
                case TASK: countTask++; break;
                case STATE: countState++; break;
                case ARROW_OPEN:
                case INITIAL_TRANSITION:
                case FINAL_TRANSITION:
                    countTransition++; break;
                case OBJECT: countObject++; break;
                case OBJECT_TYPE: countObjectType++; break;
                default: break;
            }
        }

        switch (modelKind) {
            case IMPACT_MODEL:
                if (countTask == 0) warnings.add("Impact model appears to contain no Task shapes — completeness rule: include atomic tasks inside the diagram.");
                break;
            case STATE_MODEL:
                if (countState == 0) warnings.add("State model appears to contain no State shapes — a top-level state machine requires at least one state.");
                if (countTransition == 0) warnings.add("State model contains no transitions — consider adding transition shapes (open/initial/final).");
                break;
            case OBJECT_MODEL:
                if (countObject == 0 && countObjectType == 0) warnings.add("Object model appears to contain no objects — add Object or Object Type shapes to satisfy completeness.");
                break;
            default:

                break;
        }

        return warnings;
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

            return new ShapeRecord(sel.tool, sel.shape, sel.color, sel.stroke,
                sel.x1, sel.y1, sel.x2, sel.y2,
                text, font, sel.entityId, sel.localId, sel.anchorFromId, sel.anchorToId);
        } else {
            switch (sel.tool) {
                case ACTOR:

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



        if (!isConnectorTool(sel.tool)) {
            newText = sel.tool == Tool.OBJECT_TYPE
                ? uniquifyObjectTypeLabel(newText, index)
                : uniquifyLabel(newText, index);
        }
        ShapeRecord nr = growShapeForLabel(sel, newText);
        if (nr == null) return;
        

        ShapeRecord before = copyShapeRecord(sel);
        ShapeRecord after = copyShapeRecord(nr);



        shapes.set(index, nr);
        if (!isConnectorTool(nr.tool)) {
            reanchorConnectorsFor(nr.localId);
        } else {
            redrawBuffer();
            repaint();
        }
        

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
     * Keeps larger containers behind smaller shapes while leaving connectors on top.
     */
    private void sortShapesByArea() {
        java.util.List<ShapeRecord> nonConn = new java.util.ArrayList<>();
        java.util.List<ShapeRecord> conn = new java.util.ArrayList<>();
        for (ShapeRecord r : shapes) {
            if (isConnectorTool(r.tool)) conn.add(r);
            else nonConn.add(r);
        }


        nonConn.sort((a, b) -> {
            int areaCmp = Double.compare(shapeArea(b), shapeArea(a));
            if (areaCmp != 0) return areaCmp;
            if (a.tool == Tool.PROCESS && b.tool != Tool.PROCESS) return -1;
            if (b.tool == Tool.PROCESS && a.tool != Tool.PROCESS) return 1;
            return 0;
        });
        shapes.clear();
        shapes.addAll(nonConn);
        shapes.addAll(conn);
    }

    private double shapeArea(ShapeRecord r) {
        if (r == null || r.shape == null) return 0;
        java.awt.geom.Rectangle2D b = r.shape.getBounds2D();
        return b.getWidth() * b.getHeight();
    }



    private int hitTest(int x, int y) {

        Point2D p = new Point2D.Double(x, y);
        for (int i = shapes.size() - 1; i >= 0; i--) {
            ShapeRecord r = shapes.get(i);
            if (r == null || r.shape == null) continue;

            if (isConnectorTool(r.tool)) {
                Shape pick = new BasicStroke(Math.max(6f, r.stroke + 6f)).createStrokedShape(r.shape);
                if (pick.contains(p)) return i;
                continue;
            }

            if (r.tool == Tool.PROCESS) {
                Rectangle2D bounds = r.shape.getBounds2D();
                if (bounds != null && bounds.contains(p)) return i;
            }


            try {
                if (r.shape.contains(p)) return i;
            } catch (Exception ignored) {}

            if (r.tool == Tool.ACTOR) {
                Rectangle2D actorBounds = actorAnchorBounds(r);
                if (actorBounds != null && actorBounds.contains(p)) return i;
            }


            Shape pick = new BasicStroke(Math.max(4f, r.stroke + 2f)).createStrokedShape(r.shape);
            if (pick.contains(p)) return i;
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

    private ShapeRecord clampRecordWithinCanvas(ShapeRecord record) {
        if (record == null || isConnectorTool(record.tool)) return record;

        Rectangle2D bounds = record.shape != null ? record.shape.getBounds2D() : null;
        if (bounds == null) return record;

        double dx = 0.0;
        double dy = 0.0;
        if (bounds.getX() < CANVAS_EDGE_PADDING) dx = CANVAS_EDGE_PADDING - bounds.getX();
        if (bounds.getY() < CANVAS_EDGE_PADDING) dy = CANVAS_EDGE_PADDING - bounds.getY();
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return record;

        Shape movedShape = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(record.shape);
        return new ShapeRecord(
            record.tool,
            movedShape,
            record.color,
            record.stroke,
            record.x1 + dx,
            record.y1 + dy,
            record.x2 + dx,
            record.y2 + dy,
            record.text,
            record.font,
            record.entityId,
            record.localId,
            record.anchorFromId,
            record.anchorToId
        );
    }

    private void ensureShapeVisible(ShapeRecord record) {
        if (record == null) return;
        Rectangle visible = zoomedBounds(record.x1, record.y1, Math.max(40.0, Math.abs(record.x2 - record.x1)), Math.max(40.0, Math.abs(record.y2 - record.y1)));
        visible.grow(40, 40);
        scrollRectToVisible(visible);
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

        if (t == Tool.PROCESS) {
            rw = PROCESS_DEFAULT_WIDTH;
            rh = PROCESS_DEFAULT_HEIGHT;
            rx = x1;
            ry = y1;
            x2 = x1 + rw;
            y2 = y1 + rh;
        } else if (isConstrainedSizeTool(t)) {

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
            case TASK:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                text = "Task";
                break;
            case PROCESS:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, 16, 16);
                text = "Process";
                break;
            case OBJECT:
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
            case ACTION:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                text = getDefaultLabelForTool(t);
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

        if (t == Tool.PROCESS && rw <= PROCESS_CLICK_THRESHOLD && rh <= PROCESS_CLICK_THRESHOLD) {
            rw = PROCESS_DEFAULT_WIDTH;
            rh = PROCESS_DEFAULT_HEIGHT;
            rx = x1;
            ry = y1;
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
            case TASK:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                text = "Task";
                break;
            case OBJECT:
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
            case ACTION:
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
            case TASK:
            case OBJECT:
            case ACTION: {

                g.draw(r.shape);

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
            case PROCESS: {
                drawProcessBox(g, r);
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
                double curveOffset = getParallelConnectorCurveOffset(r);
                if (Math.abs(curveOffset) > 1e-6) {
                    drawCurvedConnector(g, r, curveOffset);
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


                Point2D.Double baseAll = computeArrowBase(r.x1, r.y1, tipX, tipY, r.stroke);
                

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
                

                if (r.tool == Tool.INITIAL_TRANSITION) {
                    double circleRadius = Math.max(6, r.stroke * 2);
                    g.fill(new Ellipse2D.Double(r.x1 - circleRadius, r.y1 - circleRadius, circleRadius * 2, circleRadius * 2));
                }
                

                drawArrowHead(g, r.x1, r.y1, tipX, tipY, r.tool);
                

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

                try {
                    Rectangle2D bounds = r.shape.getBounds2D();
                    if (r.text != null) {
                        Font f = normalizeTextFont(r.font);
                        g.setFont(f);
                        drawTextLayout(g, r.text, f, bounds, r.color != null ? r.color : g.getColor());
                    }
                } catch (Exception ex) {

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


        if (preview != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Composite prevComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            drawRecord(g, preview, true);
            g.setComposite(prevComp);
        }


        if (selectedIndex >= 0 && selectedIndex < shapes.size() && currentTool != Tool.PAN) {
            ShapeRecord sel = shapes.get(selectedIndex);

            g.setColor(Color.BLUE);
            g.setStroke(new BasicStroke(1f));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (sel.tool == Tool.PROCESS) {
                Rectangle2D pb = sel.shape != null ? sel.shape.getBounds2D() : null;
                if (pb != null) {
                    g.draw(new RoundRectangle2D.Double(pb.getX(), pb.getY(), pb.getWidth(), pb.getHeight(), 16, 16));
                }
            } else {
                g.draw(sel.shape);
            }

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

    /**
     * Draws the connector head geometry for the supplied connector tool kind.
     */
    private void drawArrowHead(Graphics2D g, double x1, double y1, double x2, double y2, Tool kind) {

        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double ux = dx / len, uy = dy / len;
        double px = -uy, py = ux; // perp

        double headLen = Math.max(8, 6 + strokeWidth * 2);
        double headWidth = Math.max(12, 10 + strokeWidth * 1.5);


        double bx = x2 - ux * headLen;
        double by = y2 - uy * headLen;


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


                g.draw(new Line2D.Double(x2, y2, lx, ly));
                g.draw(new Line2D.Double(lx, ly, rx, ry));
                g.draw(new Line2D.Double(rx, ry, rxSide, rySide));
                g.draw(new Line2D.Double(rxSide, rySide, x2, y2));
                break;
            }
            case ARROW_OPEN:
            case INITIAL_TRANSITION:
            case FINAL_TRANSITION:
            case IMPACT:
            case REFERENCE: {

                g.draw(new Line2D.Double(x2, y2, sx1, sy1));
                g.draw(new Line2D.Double(x2, y2, sx2, sy2));
                break;
            }
            case ARROW_EMPTY: {

                g.draw(new Line2D.Double(x2, y2, sx1, sy1));
                g.draw(new Line2D.Double(x2, y2, sx2, sy2));
                g.draw(new Line2D.Double(sx1, sy1, sx2, sy2));  // base line
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
        Color topShade = new Color(200, 200, 200);
        Color sideShade = new Color(160, 160, 160);

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
        

        Rectangle2D mainRect = new Rectangle2D.Double(x, y + tabHeight, w, h - tabHeight);
        

        Rectangle2D tab = new Rectangle2D.Double(x, y, tabWidth, tabHeight);
        
        Color base = r.color != null ? r.color : g.getColor();
        g.setColor(base);
        

        g.draw(mainRect);
        

        g.draw(tab);
        

        g.drawLine((int)(x + tabWidth), (int)(y + tabHeight), (int)x, (int)(y + tabHeight));
        
        if (shouldSuppressLabelPaint(r)) {
            return;
        }


        g.setFont(fitFont);
        FontMetrics fm = g.getFontMetrics(fitFont);
        int textWidth = fm.stringWidth(drawLabel);
        float textX = (float) (x + (tabWidth - textWidth) / 2.0);
        float textY = (float) (y + (tabHeight - fm.getHeight()) / 2.0 + fm.getAscent());
        g.setColor(base);
        g.drawString(drawLabel, textX, textY);
    }

    private void drawProcessBox(Graphics2D g, ShapeRecord r) {
        Rectangle2D bounds = r.shape.getBounds2D();
        double x = bounds.getX();
        double y = bounds.getY();
        double w = bounds.getWidth();
        double h = bounds.getHeight();

        String label = (r.text != null && !r.text.isBlank()) ? r.text.split("\\R", 2)[0].trim() : "Process";
        Font baseFont = normalizeTextFont(r.font);

        Color base = r.color != null ? r.color : g.getColor();
        g.setColor(base);
        


        g.draw(new RoundRectangle2D.Double(x, y, w, h, 16, 16));
        
        if (shouldSuppressLabelPaint(r)) {
            return;
        }


        g.setFont(baseFont);
        FontMetrics fm = g.getFontMetrics(baseFont);
        float textX = (float) (x + 8.0);
        float textY = (float) (y + fm.getAscent() + 4.0);
        g.setColor(base);
        g.drawString(label, textX, textY);
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

        int lineHeight = fm.getHeight();
        int maxLineWidth = 0;
        for (String line : lines) {
            String text = line != null ? line.trim() : "";
            if (!text.isEmpty()) {
                maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(text));
            }
        }
        int labelWidth = Math.max(1, maxLineWidth);
        int labelHeight = Math.max(lineHeight, lines.length * lineHeight);

        double halfBoxOnNormal = (Math.abs(nx) * labelWidth + Math.abs(ny) * labelHeight) / 2.0;
        double offset = Math.max(10.0, halfBoxOnNormal + 8.0);
        double midX = (r.x1 + r.x2) / 2.0 + nx * offset;
        double midY = (r.y1 + r.y2) / 2.0 + ny * offset;

        g2.translate(midX, midY);

        java.awt.Shape labelBg = new RoundRectangle2D.Double(
            -labelWidth / 2.0 - 6.0,
            -(labelHeight / 2.0) - 3.0,
            labelWidth + 12.0,
            labelHeight + 6.0,
            10.0,
            10.0
        );
        Color bg = getBackground();
        if (bg != null) {
            g2.setColor(bg);
            g2.fill(labelBg);
        }
        g2.setColor(r.color != null ? r.color : g.getColor());

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

        double cx = arc.getCenterX() + 10;
        double cy = arc.getCenterY() + 10;

        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getAscent() - fm.getDescent();

        float tx = (float) (cx - textWidth / 2.0);
        float ty = (float) (cy + textHeight / 2.0);
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
        Font nameFont = baseFont;
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

        double tabWidth = BOUNDARY_TAB_WIDTH;
        double tabHeight = BOUNDARY_TAB_HEIGHT;
        
        Path2D p = new Path2D.Double();

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
        setModel(null);
        undoManager.discardAllEdits();
        currentMoveEdit = null;
        preview = null;
        selectedIndex = -1;
        selectAllActive = false;
        updateUndoRedoState();
        updateCanvasExtent();
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
        CanvasSnapshot snapshot = new CanvasSnapshot(new ArrayList<>(shapes), zoomScale, documentModelTypeName);
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
        undoManager.discardAllEdits();
        selectedIndex = -1;
        preview = null;
        currentMoveEdit = null;
        loadedDocumentModelTypeName = snapshot != null ? snapshot.modelTypeName : null;
        if (loadedDocumentModelTypeName != null && !loadedDocumentModelTypeName.isBlank()) {
            documentModelTypeName = loadedDocumentModelTypeName;
        }
        if (snapshot != null && snapshot.shapes != null) {
            shapes.addAll(snapshot.shapes);
        }
        if (snapshot != null && snapshot.zoomScale > 0.0) {
            setZoom(snapshot.zoomScale);
        } else {
            setZoom(1.0);
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

            if (t == Tool.SELECT || t == Tool.PAN || t == Tool.TEXT) preview = null;

            firePropertyChange("currentTool", old, t);
        }
    }


    public Tool getCurrentTool() {
        return currentTool;
    }


    public Color getDrawColor() {
        return drawColor;
    }

    public void addStatusConsumer(Consumer<String> c) {
        this.statusConsumer = c;
    }


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


    public void addDefaultText(String text) {
        if (text == null || text.isEmpty()) text = "Text";
        int dw = Math.min(300, Math.max(80, getWidth() / 6));
        int dh = Math.min(120, Math.max(20, getHeight() /12));
        int x = Math.max(10, (getWidth() - dw) / 2);
        int y = Math.max(10, (getHeight() - dh) / 2);


        Font f = defaultTextFont();
        ShapeRecord r = ShapeRecord.textRecord(text, f, drawColor, strokeWidth, x, y, dw,  dh);
        shapes.add(r);
        redrawBuffer();
        repaint();
    }


    public void addTextAt(String text, int x, int y, int w, int h) {
        if (text == null) text = "";
        Font f = defaultTextFont();

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

            model.addEntity(ent);

            Integer idx = idToIndex.get(ent.getId());
            if (idx == null) {

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

            int idx = shapes.size() - 1;
            addUndoableEdit(new TextCreateEdit(idx, copyShapeRecord(r)));
            redrawBuffer();
            repaint();
        }
    }


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


        ta.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "commit");
        ta.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { finish.run(); }
        });

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


    public void updateSelectedText(String newText, Font newFont, Color newColor) {
        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            ShapeRecord sel = shapes.get(selectedIndex);
            if (sel.tool != Tool.TEXT) return;

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

            ShapeRecord before = copyShapeRecord(sel);
            ShapeRecord after = copyShapeRecord(nr);
            if (sel.entityId != null && model != null) {

                model.updateEntity(entityFromShape(after));
            } else {
                shapes.set(selectedIndex, nr);
                redrawBuffer();
                repaint();
            }
            addUndoableEdit(new TextEdit(selectedIndex, before, after));
        }
    }


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

        boolean canU = undoManager.canUndo();
        boolean canR = undoManager.canRedo();
        firePropertyChange("canUndo", !canU, canU);
        firePropertyChange("canRedo", !canR, canR);
    }
}