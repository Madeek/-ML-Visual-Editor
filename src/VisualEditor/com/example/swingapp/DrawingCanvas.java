// This class implements a drawing canvas with basic shape tools, selection, and editing.
package com.example.swingapp;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

public class DrawingCanvas extends JComponent {
    private BufferedImage buf;
    private Color drawColor = Color.BLACK;
    private float strokeWidth = 3f;
    private int lastX = -1, lastY = -1;
    private Consumer<String> statusConsumer = s -> {};
    private final UndoManager undoManager = new UndoManager();

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
        final double x1, y1, x2, y2;
        // text-specific
        final String text;
        final Font font;

        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2, String text, Font font) {
            this.tool = tool;
            this.shape = shape;
            this.color = color;
            this.stroke = stroke;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.text = text; this.font = font;
        }

        // convenience constructor for non-text shapes
        ShapeRecord(Tool tool, Shape shape, Color color, float stroke, double x1, double y1, double x2, double y2) {
            this(tool, shape, color, stroke, x1, y1, x2, y2, null, null);
        }

        // convenience factory for text records (bounding rect + text/font)
        static ShapeRecord textRecord(String text, Font font, Color color, float stroke, double x, double y, double w, double h) {
            Shape rect = new Rectangle2D.Double(x, y, w, h);
            return new ShapeRecord(Tool.TEXT, rect, color, stroke, x, y, x + w, y + h, text, font);
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
                        selectedIndex = hit;
                        // check if clicked on a handle
                        Rectangle2D bounds = getShapeBounds(shapes.get(selectedIndex));
                        activeHandle = handleHit(bounds, lastX, lastY);
                        if (activeHandle >= 0) {
                            resizing = true;
                        } else {
                            draggingMove = true;
                        }
                        repaint();
                    } else {
                        // clicked empty area -> clear selection
                        selectedIndex = -1;
                        repaint();
                    }
                    return;
                }

                // other drawing tools: set preview
                preview = createPreview(lastX, lastY, lastX, lastY);
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int x = e.getX(), y = e.getY();
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
                            Shape moved = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(sel.shape);
                            ShapeRecord nr = new ShapeRecord(sel.tool, moved, sel.color, sel.stroke,
                                    sel.x1 + dx, sel.y1 + dy, sel.x2 + dx, sel.y2 + dy);
                            shapes.set(selectedIndex, nr);
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
                            ShapeRecord nr = createRecordFromTool(sel.tool, sel.color, sel.stroke, (int)x1, (int)y1, (int)x2, (int)y2);
                            if (nr != null) {
                                shapes.set(selectedIndex, nr);
                                redrawBuffer();
                                repaint();
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
                        shapes.add(preview);
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
                    redrawBuffer();
                    repaint();
                    statusConsumer.accept("Ready");
                    return;
                }

                if (preview != null) {
                    shapes.add(preview);
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
            currentTool = t;
            // when switching to select, clear preview
            if (t == Tool.SELECT) preview = null;
        }
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
     * Delete the currently selected shape, if any. 
    */
    public void deleteSelectedShape() {
        if (selectedIndex >= 0 && selectedIndex < shapes.size()) {
            shapes.remove(selectedIndex);
            selectedIndex = -1;
            redrawBuffer();
            repaint();
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
            shapes.set(selectedIndex, nr);
            redrawBuffer();
            repaint();
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