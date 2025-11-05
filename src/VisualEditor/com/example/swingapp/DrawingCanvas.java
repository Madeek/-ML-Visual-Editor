// This class implements a drawing canvas with basic shape tools, selection, and editing.
package com.example.swingapp;

import com.example.swingapp.model.ReMoDeLEntity;
import com.example.swingapp.model.ReMoDeLModel;
import com.example.swingapp.model.Concept;

import javax.swing.*;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import javax.swing.undo.AbstractUndoableEdit;

import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.text.LineBreakMeasurer;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.text.CharacterIterator;
import java.util.*;
import java.util.function.Consumer;
import java.util.List;

public class DrawingCanvas extends JComponent {
    private BufferedImage buf;
    private Color drawColor = Color.BLACK;
    private float strokeWidth = 3f;
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
            return new ShapeRecord(Tool.TEXT, rect, r.color, r.stroke, r.x1, r.y1, r.x2, r.y2, r.text, r.font, r.entityId);
        } else {
            // use existing factory to rebuild shape from coords
            ShapeRecord nr = createRecordFromTool(r.tool, r.color, r.stroke, (int) Math.round(r.x1), (int) Math.round(r.y1), (int) Math.round(r.x2), (int) Math.round(r.y2));
            if (nr != null) {
                return new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2, nr.text, nr.font, r.entityId);
            }
            return r;
        }
    }

    // --- Model wiring -----------------------------------------------------------------
    private com.example.swingapp.model.ModelListener modelListener = null;

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
        modelListener = new com.example.swingapp.model.ModelListener() {
            @Override
            public void modelChanged(com.example.swingapp.model.ModelEvent e) {
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
        for (int i = 0; i < all.size(); i++) {
            ReMoDeLEntity e = all.get(i);
            ShapeRecord r = shapeFromEntity(e);
            if (r != null) {
                shapes.add(r);
                if (r.entityId != null) idToIndex.put(r.entityId, i);
            }
        }
        redrawBuffer();
        repaint();
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
            int fontSize = e.get("fontSize") instanceof Number ? ((Number)e.get("fontSize")).intValue() : Math.max(12, (y2 - y1) / 2);
            int rgb = e.get("colorRGB") instanceof Number ? ((Number)e.get("colorRGB")).intValue() : Color.BLACK.getRGB();
            Font f = new Font(fontName, fontStyle, fontSize);
            Color c = new Color(rgb, true);
            int w = Math.max(4, x2 - x1);
            int h = Math.max(4, y2 - y1);
            return ShapeRecord.textRecord(txt, f, c, strokeWidth, x1, y1, w, h, e.getId());
        }
        // other types: try to read bbox and draw a rectangle placeholder
        Object ox1 = e.get("x1"); Object oy1 = e.get("y1"); Object ox2 = e.get("x2"); Object oy2 = e.get("y2");
        int x1 = ox1 instanceof Number ? ((Number)ox1).intValue() : 10;
        int y1 = oy1 instanceof Number ? ((Number)oy1).intValue() : 10;
        int x2 = ox2 instanceof Number ? ((Number)ox2).intValue() : x1 + 80;
        int y2 = oy2 instanceof Number ? ((Number)oy2).intValue() : y1 + 40;
        Shape s = new Rectangle2D.Double(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
        return new ShapeRecord(Tool.RECTANGLE, s, Color.BLACK, strokeWidth, x1, y1, x2, y2, null, null, e.getId());
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
            if (r.font != null) {
                ent.put("fontName", r.font.getName());
                ent.put("fontStyle", r.font.getStyle());
                ent.put("fontSize", r.font.getSize());
            }
            if (r.color != null) ent.put("colorRGB", r.color.getRGB());
            return ent;
        }
        ent.setType("shape");
        ent.put("x1", (int) Math.round(r.x1));
        ent.put("y1", (int) Math.round(r.y1));
        ent.put("x2", (int) Math.round(r.x2));
        ent.put("y2", (int) Math.round(r.y2));
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

    // tools
    public enum Tool {
        SELECT, DELETE, FREEHAND, LINE,
        ARROW_FILLED, ARROW_DIAMOND, ARROW_OPEN,
        OVAL, RECTANGLE, ROUNDED_RECTANGLE,
        TEXT
    }
    private Tool currentTool = Tool.SELECT;

    // stored shapes
    private final java.util.List<ShapeRecord> shapes = new ArrayList<>();
    private ShapeRecord preview = null;

    // optional backing model and mapping from entity id -> shape index
    private ReMoDeLModel model = null;
    private final java.util.Map<String, Integer> idToIndex = new java.util.HashMap<>();

    // selection/edit state
    private int selectedIndex = -1;
    private boolean draggingMove = false;
    private boolean resizing = false;
    private int activeHandle = -1; // 0..3 corners
    private int pressX, pressY;

    private static final int HANDLE_SIZE = 8;

    public DrawingCanvas() {
        setPreferredSize(new Dimension(1600, 1200));
        setBackground(Color.WHITE);
        setOpaque(true);
        initMouse();
        setFocusable(true);
    }

    private static class ShapeRecord {
        final Tool tool;
        final Shape shape; // primary geometry (Line2D, Path2D, Rect/Ellipse)
        final Color color;
        final float stroke;
    // extra data for arrows and geometry
    final String entityId; // optional associated model entity id (if this shape is backed by ReMoDeLModel)
        final double x1, y1, x2, y2;
        // text-specific
        final String text;
        final Font font;

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text, Font font) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, text, font, null);
        }

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text, Font font, String entityId) {
            this.tool = tool;
            this.shape = shape;
            this.color = color;
            this.stroke = stroke;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.text = text; this.font = font;
            this.entityId = entityId;
        }

        // convenience constructor for non-text shapes
        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, null, null);
        }

        // convenience factory for text records (bounding rect + text/font)
        static ShapeRecord textRecord(String text, Font font, Color color, float stroke, double x, double y, double w, double h) {
            return textRecord(text, font, color, stroke, x, y, w, h, null);
        }

        static ShapeRecord textRecord(String text, Font font, Color color, float stroke, double x, double y, double w, double h, String entityId) {
            Shape rect = new Rectangle2D.Double(x, y, w, h);
            return new ShapeRecord(Tool.TEXT, rect, color, stroke, x, y, x + w, y + h, text, font, entityId);
        }
    }

    private void ensureBuffer() {

        if (buf == null || buf.getWidth() != getWidth() || buf.getHeight() != getHeight()) {
            redrawBuffer();
        }
    }

    private void redrawBuffer() {

        BufferedImage newBuf = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = newBuf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // clear background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newBuf.getWidth(), newBuf.getHeight());
        // redraw existing shapes into new buffer
        for (ShapeRecord r : shapes) drawRecord(g, r, false);
        g.dispose();
        buf = newBuf;
    }

    private void initMouse() {

        MouseAdapter ma = new MouseAdapter() {
            private GeneralPath freePath;

            @Override
            public void mousePressed(MouseEvent e) {
                pressX = lastX = e.getX();
                pressY = lastY = e.getY();
                statusConsumer.accept("Drawing...");
                if (currentTool == Tool.FREEHAND) {
                    freePath = new GeneralPath();
                    freePath.moveTo(lastX, lastY);
                    preview = new ShapeRecord(Tool.FREEHAND, freePath, drawColor, strokeWidth, lastX, lastY, lastX, lastY);
                    repaint();
                    return;
                }

                if (currentTool == Tool.SELECT) {
                    // hit-test shapes from top-most to bottom
                    int hit = hitTest(lastX, lastY);
                    if (hit >= 0) {
                        // if double-clicked a text item, start inline editing
                        if (e.getClickCount() == 2 && shapes.get(hit).tool == Tool.TEXT) {
                            startEditingText(hit);
                            return;
                        }
                        selectedIndex = hit;
                        // check if clicked on a handle
                        Rectangle2D bounds = getShapeBounds(shapes.get(selectedIndex));
                        activeHandle = handleHit(bounds, lastX, lastY);
                        if (activeHandle >= 0) {
                            resizing = true;
                        } else {
                            draggingMove = true;
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
                    return;
                }

                // other drawing tools: set preview (but TEXT uses separate placer and shouldn't show preview)
                if (currentTool != Tool.TEXT) {
                    preview = createPreview(lastX, lastY, lastX, lastY);
                } else {
                    preview = null;
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int x = e.getX(), y = e.getY();
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

                if (currentTool == Tool.SELECT) {
                    if (selectedIndex >= 0) {
                        ShapeRecord sel = shapes.get(selectedIndex);
                        int dx = x - lastX, dy = y - lastY;
                        if (draggingMove) {
                            // translate shape by dx,dy
                            if (sel.tool == Tool.TEXT) {
                                // preserve text and font when translating
                                double nx1 = sel.x1 + dx, ny1 = sel.y1 + dy, nx2 = sel.x2 + dx, ny2 = sel.y2 + dy;
                                Shape rect = new Rectangle2D.Double(Math.min(nx1, nx2), Math.min(ny1, ny2), Math.abs(nx2 - nx1), Math.abs(ny2 - ny1));
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT, rect, sel.color, sel.stroke, nx1, ny1, nx2, ny2, sel.text, sel.font, sel.entityId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                }
                            } else {
                                Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                                ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                        sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy, null, null, sel.entityId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                }
                            }
                            redrawBuffer();
                            repaint();
                        } else if (resizing) {
                            // compute new bounds using which corner is dragged
                            Rectangle2D b = getShapeBounds(sel);
                            double x1 = b.getX(), y1 = b.getY(), x2 = b.getX() + b.getWidth(), y2 = b.getY() + b.getHeight();
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
                            }
                            if (sel.tool == Tool.TEXT) {
                                // resize text box, preserve text/font
                                ShapeRecord nr = new ShapeRecord(Tool.TEXT,
                                        new Rectangle2D.Double(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1)),
                                        sel.color, sel.stroke, x1, y1, x2, y2, sel.text, sel.font, sel.entityId);
                                if (sel.entityId != null && model != null) {
                                    model.updateEntity(entityFromShape(nr));
                                } else {
                                    shapes.set(selectedIndex, nr);
                                }
                                redrawBuffer();
                                repaint();
                            } else {
                                ShapeRecord nr = createRecordFromTool(sel.tool, sel.color, sel.stroke, (int)x1, (int)y1, (int)x2, (int)y2);
                                if (nr != null) {
                                    if (sel.entityId != null && model != null) {
                                        // preserve entity id
                                        ShapeRecord withId = new ShapeRecord(nr.tool, nr.shape, nr.color, nr.stroke, nr.x1, nr.y1, nr.x2, nr.y2, nr.text, nr.font, sel.entityId);
                                        model.updateEntity(entityFromShape(withId));
                                    } else {
                                        shapes.set(selectedIndex, nr);
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
                int x = e.getX(), y = e.getY();
                if (currentTool == Tool.FREEHAND) {
                    if (preview != null) {
                        if (model != null) {
                            ReMoDeLEntity ent = entityFromShape(preview);
                            model.addEntity(ent);
                        } else {
                            shapes.add(preview);
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

                if (currentTool == Tool.SELECT) {
                    // finish move/resize
                    draggingMove = false; resizing = false; activeHandle = -1;
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

                if (preview != null) {
                    if (model != null) {
                        ReMoDeLEntity ent = entityFromShape(preview);
                        model.addEntity(ent);
                    } else {
                        shapes.add(preview);
                    }
                    Graphics2D g = getBufferGraphics();
                    drawRecord(g, preview, false);
                    g.dispose();
                    preview = null;
                }
                lastX = lastY = -1;
                statusConsumer.accept("Ready");
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                ensureBuffer();
                repaint();
            }
        });
    }

    private int hitTest(int x, int y) {

        Point2D p = new Point2D.Double(x, y);
        for (int i = shapes.size() - 1; i >= 0; i--) {
            ShapeRecord r = shapes.get(i);
            Shape pick = new BasicStroke(Math.max(6f, r.stroke + 6f)).createStrokedShape(r.shape);
            if (pick.contains(p)) return i;
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

        Rectangle2D[] handles = new Rectangle2D[] {
                new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tl
                new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tr
                new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // br
                new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE) // bl
        };
        for (int i = 0; i < handles.length; i++) if (handles[i].contains(x, y)) return i;
        return -1;
    }

    private ShapeRecord createPreview(int x1, int y1, int x2, int y2) {
        
        Shape s = null;
        Tool t = currentTool;
        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1);
        int rh = Math.abs(y2 - y1);

        switch (t) {

            case LINE:
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_OPEN:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case OVAL:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                break;
            case RECTANGLE:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                break;
            case ROUNDED_RECTANGLE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                break;
            default:
                s = new Line2D.Double(x1, y1, x2, y2);
        }
        return new ShapeRecord(t, s, drawColor, strokeWidth, x1, y1, x2, y2);
    }

    private ShapeRecord createRecordFromTool(Tool t, Color c, float sWidth, int x1, int y1, int x2, int y2) {

        // helper to construct a new shape record with normalized coords
        int rx = Math.min(x1, x2);
        int ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1);
        int rh = Math.abs(y2 - y1);
        Shape s = null;
        switch (t) {
            case LINE:
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_OPEN:
                s = new Line2D.Double(x1, y1, x2, y2);
                break;
            case OVAL:
                s = new Ellipse2D.Double(rx, ry, rw, rh);
                break;
            case RECTANGLE:
                s = new Rectangle2D.Double(rx, ry, rw, rh);
                break;
            case ROUNDED_RECTANGLE:
                s = new RoundRectangle2D.Double(rx, ry, rw, rh, Math.max(8, Math.min(rw, rh) / 4.0), Math.max(8, Math.min(rw, rh) / 4.0));
                break;
            case FREEHAND:
            default:
                // fallback to a tiny line
                s = new Line2D.Double(x1, y1, x2, y2);
        }
        return new ShapeRecord(t, s, c, sWidth, x1, y1, x2, y2);
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
        double headLen = Math.max(8, 6 + stroke * 2); // match drawArrowHead's headLen
        double bx = x2 - ux * headLen;
        double by = y2 - uy * headLen;
        return new Point2D.Double(bx, by);
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
            case OVAL:
            case RECTANGLE:
            case ROUNDED_RECTANGLE:
                g.draw(r.shape);
                break;
            case ARROW_FILLED:
            case ARROW_DIAMOND:
            case ARROW_OPEN: {
                // compute base of arrow head (same logic for all kinds) and draw shaft only to that base
                Point2D.Double baseAll = computeArrowBase(r.x1, r.y1, r.x2, r.y2, r.stroke);
                Line2D shaftAll = new Line2D.Double(r.x1, r.y1, baseAll.x, baseAll.y);
                g.draw(shaftAll);
                // draw head using the same stroke so geometry matches
                drawArrowHead(g, r.x1, r.y1, r.x2, r.y2, r.tool);
                break;
            }
            case TEXT: {
                // draw multi-line / wrapped text within the shape bounds
                try {
                    Rectangle2D bounds = r.shape.getBounds2D();
                    if (r.text != null) {
                        Font f = r.font != null ? r.font : g.getFont();
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
        g.drawImage(buf, 0, 0, this);
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
        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            ShapeRecord sel = shapes.get(selectedIndex);
            Rectangle2D b = getShapeBounds(sel);
            if (b != null) {
                g.setColor(Color.BLUE);
                g.setStroke(new BasicStroke(1f));
                g.draw(b);
                // handles
                double hx = b.getX(), hy = b.getY(), hw = b.getWidth(), hh = b.getHeight();
                Rectangle2D[] handles = new Rectangle2D[] {
                        new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tl
                        new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // tr
                        new Rectangle2D.Double(hx + hw - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE), // br
                        new Rectangle2D.Double(hx - HANDLE_SIZE/2, hy + hh - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE) // bl
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
        double headWidth = Math.max(6, 4 + strokeWidth * 1.5);

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
                // diamond center at bx - ux*(headLen/2)
                double cx = bx - ux * (headLen / 2.0);
                double cy = by - uy * (headLen / 2.0);
                Path2D d = new Path2D.Double();
                d.moveTo(x2, y2);
                d.lineTo(sx1, sy1);
                d.lineTo(cx, cy);
                d.lineTo(sx2, sy2);
                d.closePath();
                g.fill(d);
                break;
            }
            case ARROW_OPEN: {
                // draw two lines forming open head
                g.draw(new Line2D.Double(x2, y2, sx1, sy1));
                g.draw(new Line2D.Double(x2, y2, sx2, sy2));
                break;
            }
            default:
                break;
        }
    }

    /**
     * Draw text with simple word-wrapping inside the given bounds using FontMetrics.
     * This avoids dependencies on java.text.LineBreakMeasurer/TextLayout so it compiles
     * cleanly in minimal module configurations.
     */
    private void drawTextLayout(Graphics2D g, String text, Font font, Rectangle2D bounds, Color color) {
        if (text == null || text.isEmpty() || bounds == null) return;
        g.setFont(font != null ? font : g.getFont());
        g.setColor(color != null ? color : g.getColor());
        FontMetrics fm = g.getFontMetrics(g.getFont());
        int wrapWidth = Math.max(4, (int) bounds.getWidth() - 8);
        float x = (float) (bounds.getX() + 4f);
        float y = (float) (bounds.getY() + 4f) + fm.getAscent();

        String[] paragraphs = text.split("\r?\n");
        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p].trim();
            if (paragraph.isEmpty()) {
                y += fm.getHeight();
                if (y > bounds.getY() + bounds.getHeight()) break;
                continue;
            }
            String[] words = paragraph.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                String test = line.length() == 0 ? word : line + " " + word;
                int w = fm.stringWidth(test);
                if (w > wrapWidth && line.length() > 0) {
                    // draw current line
                    g.drawString(line.toString(), x, y);
                    y += fm.getHeight();
                    if (y > bounds.getY() + bounds.getHeight()) return;
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (line.length() > 0) line.append(' ');
                    line.append(word);
                }
            }
            if (line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += fm.getHeight();
                if (y > bounds.getY() + bounds.getHeight()) return;
            }
            // add paragraph spacing
            y += fm.getLeading();
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
        repaint();
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
            // when switching to select, clear preview
            if (t == Tool.SELECT || t == Tool.TEXT) preview = null;
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
        Font f = new Font("SansSerif", Font.PLAIN, Math.max(12, dh / 2));
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
        Font f = new Font("SansSerif", Font.PLAIN, Math.max(12, h / 2));
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
        ta.setFont(sel.font != null ? sel.font : getFont());
        JScrollPane sp = new JScrollPane(ta);
        sp.setBounds((int) b.getX(), (int) b.getY(), Math.max(40, (int) b.getWidth()), Math.max(24, (int) b.getHeight()));
        this.add(sp);
        this.revalidate();
        this.repaint();
        ta.requestFocusInWindow();
        ta.selectAll();

        Runnable finish = () -> {
            String txt = ta.getText();
            DrawingCanvas.this.remove(sp);
            // update the selected text record
            updateSelectedText(txt, ta.getFont(), ta.getForeground());
            DrawingCanvas.this.revalidate();
            DrawingCanvas.this.repaint();
        };

        Runnable cancel = () -> {
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
        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            ShapeRecord sel = shapes.get(selectedIndex);
            if (sel != null && sel.entityId != null && model != null) {
                model.removeEntity(sel.entityId);
            } else {
                shapes.remove(selectedIndex);
                redrawBuffer();
                repaint();
            }
            selectedIndex = -1;
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
               newFont != null ? newFont : sel.font);
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

    private void updateUndoRedoState() {
        // fire property changes so UI can enable/disable menu/buttons
        boolean canU = undoManager.canUndo();
        boolean canR = undoManager.canRedo();
        firePropertyChange("canUndo", !canU, canU);
        firePropertyChange("canRedo", !canR, canR);
    }
}