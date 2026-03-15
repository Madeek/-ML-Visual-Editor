package com.example.swingapp.view;

import javax.swing.*;

import com.example.swingapp.model.ReMoDeLModel;
import com.example.swingapp.persistence.ReMoDeLExporter;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class ToolBarShell {
    private final JPanel toolPanel;
    private final JToolBar toolBar;
    private MouseAdapter textPlacer;
    private final JToolBar bottomBar;
    private final DrawingCanvas canvas;
    private final Map<DrawingCanvas.Tool, JToggleButton> toolButtons = new HashMap<>();
    private ButtonGroup toolGroup = new ButtonGroup();
    private JComboBox<ModelType> modelSelector;
    private JComboBox<ReferenceKind> referenceSelector;

    private enum ModelType {
        TASK_MODEL("Task Model"),
        IMPACT_MODEL("Impact Model"),
        STATE_MODEL("State Model"),
        PROCESS_MODEL("Process Model"),
        OBJECT_MODEL("Object Model");

        private final String label;

        ModelType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum IconKind {
        SELECT,
        TEXT,
        RECT,
        OVAL,
        ROUND_RECT,
        STATE,
        LINE,
        ARROW_FILLED,
        ARROW_EMPTY,
        ARROW_DIAMOND,
        ARROW_OPEN,
        IMPACT,
        REFERENCE,
        ENACTS,
        ACTOR,
        SYSTEM,
        BOUNDARY,
        OBJECT_TYPE,
        AUTHORISATION,
        INITIAL_TRANSITION,
        FINAL_TRANSITION
    }

    private enum ReferenceKind {
        PLAIN("Plain", ""),
        KIND_OF("KindOf", "kindOf"),
        PART_OF("PartOf", "partOf"),
        MADE_OF("MadeOf", "madeOf");

        private final String label;
        private final String qualifier;

        ReferenceKind(String label, String qualifier) {
            this.label = label;
            this.qualifier = qualifier;
        }

        public String qualifier() {
            return qualifier;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public ToolBarShell(DrawingCanvas canvas) {
        this.canvas = canvas;

        toolBar = new JToolBar();
        bottomBar = new JToolBar();
        bottomBar.setFloatable(false);
        toolBar.setFloatable(false);

        // wrapper that stacks two horizontal toolbars
        JPanel rows = new JPanel(new GridLayout(2, 1, 0, 0));
        rows.add(toolBar);
        rows.add(bottomBar);

        toolPanel = new JPanel(new BorderLayout());
        toolPanel.add(rows, BorderLayout.NORTH);
        
        init();
    }

    private void init() {
        canvas.setReferenceDefaultName("member");
        canvas.setReferenceQualifier("");
        // Model selector (switches tool sets)
        JLabel modelLabel = new JLabel("Model:");
        modelSelector = new JComboBox<>(ModelType.values());
        modelSelector.setSelectedItem(ModelType.TASK_MODEL);
        modelSelector.addActionListener(e -> rebuildModelTools((ModelType) modelSelector.getSelectedItem()));
        toolBar.add(modelLabel);
        toolBar.add(modelSelector);

        // Clear button
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> canvas.clear());
        toolBar.add(clearBtn);

        // Color chooser
        JButton colorBtn = new JButton("Color");
        colorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(toolBar, "Choose drawing color", Color.BLACK);
            if (chosen != null) canvas.setDrawColor(chosen);
        });
        toolBar.add(colorBtn);

        // Delete tool
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> canvas.deleteSelectedShape());
        toolBar.add(deleteBtn);

        // Selection tool (default) - use toggle buttons for tools so selection is visible
        JToggleButton selectBtn = new JToggleButton("Select", new ToolIcon(IconKind.SELECT));
        selectBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.SELECT));
        toolGroup.add(selectBtn);
        toolButtons.put(DrawingCanvas.Tool.SELECT, selectBtn);
        selectBtn.setSelected(true);
        toolBar.add(selectBtn);

        // Textbox tool
        JToggleButton textBtn = new JToggleButton("Text", new ToolIcon(IconKind.TEXT));
        textBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.TEXT));
        toolGroup.add(textBtn);
        toolButtons.put(DrawingCanvas.Tool.TEXT, textBtn);
        toolBar.add(textBtn);

        // initial tool set
        rebuildModelTools(ModelType.TASK_MODEL);

        // prepare text placer adapter (creates a JTextField at click point and commits on Enter/focus lost)
        textPlacer = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // only react to left-click when TEXT tool is active
                if (SwingUtilities.isLeftMouseButton(e) && canvas.getCurrentTool() == DrawingCanvas.Tool.TEXT) {
                    final JTextField tf = new JTextField(20);
                    // ensure absolute positioning: if canvas has a layout, switch to null layout for absolute placement
                    if (canvas.getLayout() != null) {
                        canvas.setLayout(null);
                    }
                    int prefW = 160;
                    int prefH = 24;
                    int x = e.getX();
                    int y = e.getY();
                    tf.setBounds(x, y, prefW, prefH);
                    // show border and background
                    tf.setOpaque(false);
                    tf.setBackground(Color.WHITE);
                    tf.setForeground(canvas.getDrawColor());
                    canvas.add(tf);
                    canvas.revalidate();
                    canvas.repaint();
                    tf.requestFocusInWindow();
                    tf.selectAll();

                    // finish on Enter
                    tf.addActionListener(ae -> finishTextField(tf));
                    // finish on focus lost
                    tf.addFocusListener(new FocusAdapter() {
                        @Override
                        public void focusLost(FocusEvent fe) {
                            finishTextField(tf);
                        }
                    });
                }
            }

            private void finishTextField(JTextField tf) {
                String text = tf.getText();
                Rectangle bounds = tf.getBounds();
                Container parent = tf.getParent();
                if (parent != null) {
                    parent.remove(tf);
                    if (text != null && !text.trim().isEmpty()) {
                        // add text into the canvas model (as a shape record) so it participates
                        canvas.addTextAt(text, bounds.x, bounds.y, bounds.width, bounds.height);
                    }
                    parent.revalidate();
                    parent.repaint();
                }
            }
        };

        // listen for canvas tool changes so toolbar highlights stay in sync
        canvas.addPropertyChangeListener("currentTool", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                Object nv = evt.getNewValue();
                if (nv instanceof DrawingCanvas.Tool) {
                    DrawingCanvas.Tool t = (DrawingCanvas.Tool) nv;
                    JToggleButton btn = toolButtons.get(t);
                    if (btn != null && !btn.isSelected()) btn.setSelected(true);

                    // attach/remove text placer listener based on tool
                    if (t == DrawingCanvas.Tool.TEXT) {
                        canvas.addMouseListener(textPlacer);
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                    } else if (t == DrawingCanvas.Tool.SELECT) {
                        canvas.removeMouseListener(textPlacer);
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    } else {
                        canvas.removeMouseListener(textPlacer);
                        // restore default cursor
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    }
                }
            }
        });
    }

    public JMenuBar createMenuBar(JFrame parentFrame) {
        JMenuBar menuBar = new JMenuBar();

        // --- File menu
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(e -> {
            // Clear the canvas for a new document
            canvas.clear();
        });
        fileMenu.add(newItem);

        JMenuItem saveDrawingItem = new JMenuItem("Save...");
        saveDrawingItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Drawing files (*.ser)", "ser"));
            int res = chooser.showSaveDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (!file.getName().toLowerCase().endsWith(".ser")) {
                    file = new File(file.getParentFile(), file.getName() + ".ser");
                }
                try {
                    canvas.saveDrawing(file);
                    JOptionPane.showMessageDialog(parentFrame, "Drawing saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parentFrame, "Error saving drawing: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        fileMenu.add(saveDrawingItem);

        JMenuItem openDrawingItem = new JMenuItem("Open...");
        openDrawingItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Drawing files (*.ser)", "ser"));
            int res = chooser.showOpenDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                try {
                    canvas.loadDrawing(file);
                    JOptionPane.showMessageDialog(parentFrame, "Drawing loaded successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parentFrame, "Error loading drawing: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        fileMenu.add(openDrawingItem);

        JMenuItem saveItem = new JMenuItem("Export...");
        saveItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            javax.swing.filechooser.FileNameExtensionFilter remodelFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("ReMoDeL files (*.remodel)", "remodel");
            javax.swing.filechooser.FileNameExtensionFilter xmlFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("XML files (*.xml)", "xml");
            javax.swing.filechooser.FileNameExtensionFilter jsonFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("JSON files (*.json)", "json");

            chooser.setFileFilter(remodelFilter);
            chooser.addChoosableFileFilter(xmlFilter);
            chooser.addChoosableFileFilter(jsonFilter);
            int res = chooser.showSaveDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File selected = chooser.getSelectedFile();
                String filePath = selected.getAbsolutePath();
                String lowerPath = filePath.toLowerCase();

                if (!lowerPath.endsWith(".json") && !lowerPath.endsWith(".xml") && !lowerPath.endsWith(".remodel")) {
                    javax.swing.filechooser.FileFilter chosenFilter = chooser.getFileFilter();
                    if (chosenFilter == jsonFilter) {
                        filePath = filePath + ".json";
                    } else if (chosenFilter == xmlFilter) {
                        filePath = filePath + ".xml";
                    } else {
                        filePath = filePath + ".remodel";
                    }
                }
                try {
                    ReMoDeLModel model = canvas.getExportModel();
                    if (filePath.toLowerCase().endsWith(".json")) {
                        ReMoDeLExporter.exportToJSON(model, filePath);
                    } else if (filePath.toLowerCase().endsWith(".xml")) {
                        ReMoDeLExporter.exportToXML(model, filePath);
                    } else {
                        ReMoDeLExporter.exportToRemodelModel(model, filePath, currentModelKind());
                    }         
                    JOptionPane.showMessageDialog(parentFrame, "Model exported successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parentFrame, "Error exporting: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        fileMenu.add(saveItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Close Window");
        exitItem.addActionListener(e -> {
            Window w = SwingUtilities.getWindowAncestor(toolPanel);
            if (w != null) w.dispose();
        });
        fileMenu.add(exitItem);

        // --- Edit menu
        JMenu editMenu = new JMenu("Edit");

        JMenuItem undoItem = new JMenuItem("Undo");

        // use platform menu shortcut (Ctrl on Win/Linux, Cmd on macOS)
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        undoItem.addActionListener(e -> canvas.undo());
        undoItem.setEnabled(canvas.canUndo());
        editMenu.add(undoItem);

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        redoItem.addActionListener(e -> canvas.redo());
        redoItem.setEnabled(canvas.canRedo());
        editMenu.add(redoItem);

        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyItem.addActionListener(e -> canvas.copySelectedShape());
        editMenu.add(copyItem);

        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        cutItem.addActionListener(e -> canvas.cutSelectedShape());
        editMenu.add(cutItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteItem.addActionListener(e -> canvas.pasteClipboardShape());
        editMenu.add(pasteItem);

        // --- View menu
        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem showTools = new JCheckBoxMenuItem("Show Tools", true);
        showTools.addActionListener(e -> toolPanel.setVisible(showTools.isSelected()));
        viewMenu.add(showTools);

        // Add menus to bar
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);

        // listen for canvas undo/redo availability changes
        canvas.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if ("canUndo".equals(evt.getPropertyName())) {
                    undoItem.setEnabled(Boolean.TRUE.equals(evt.getNewValue()));
                } else if ("canRedo".equals(evt.getPropertyName())) {
                    redoItem.setEnabled(Boolean.TRUE.equals(evt.getNewValue()));
                }
            }
        });

        return menuBar;
    }
    
    public JComponent getToolBar() {
        return toolPanel;
    }

    private ReMoDeLExporter.ModelKind currentModelKind() {
        ModelType selected = (ModelType) modelSelector.getSelectedItem();
        if (selected == null) return ReMoDeLExporter.ModelKind.TASK_MODEL;
        switch (selected) {
            case IMPACT_MODEL:
                return ReMoDeLExporter.ModelKind.IMPACT_MODEL;
            case OBJECT_MODEL:
                return ReMoDeLExporter.ModelKind.OBJECT_MODEL;
            case STATE_MODEL:
                return ReMoDeLExporter.ModelKind.STATE_MODEL;
            case PROCESS_MODEL:
                return ReMoDeLExporter.ModelKind.PROCESS_MODEL;
            case TASK_MODEL:
            default:
                return ReMoDeLExporter.ModelKind.TASK_MODEL;
        }
    }

    private void rebuildModelTools(ModelType type) {
        bottomBar.removeAll();
        toolButtons.keySet().removeIf(t -> t != DrawingCanvas.Tool.SELECT && t != DrawingCanvas.Tool.TEXT);
        toolGroup = new ButtonGroup();

        // re-add always-on tools to group so selection state works
        JToggleButton selectBtn = toolButtons.get(DrawingCanvas.Tool.SELECT);
        JToggleButton textBtn = toolButtons.get(DrawingCanvas.Tool.TEXT);
        if (selectBtn != null) toolGroup.add(selectBtn);
        if (textBtn != null) toolGroup.add(textBtn);

        switch (type) {
            case TASK_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.OVAL, "Task", IconKind.OVAL);
                addToolButton(bottomBar, DrawingCanvas.Tool.ACTOR, "Actor", IconKind.ACTOR);
                addToolButton(bottomBar, DrawingCanvas.Tool.SYSTEM, "System", IconKind.SYSTEM);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.LINE, "Association", IconKind.LINE);
                addToolButton(bottomBar, DrawingCanvas.Tool.ENACTS, "Enacts", IconKind.ENACTS);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_DIAMOND, "Composition", IconKind.ARROW_DIAMOND);
                break;
            case IMPACT_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.OVAL, "Task", IconKind.OVAL);
                addToolButton(bottomBar, DrawingCanvas.Tool.RECTANGLE, "Object", IconKind.RECT);
                addToolButton(bottomBar, DrawingCanvas.Tool.IMPACT, "Impact", IconKind.IMPACT);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                break;
            case STATE_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.STATE, "State", IconKind.STATE);
                addToolButton(bottomBar, DrawingCanvas.Tool.ACTOR, "Actor", IconKind.ACTOR);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_OPEN, "Transition", IconKind.ARROW_OPEN);
                addToolButton(bottomBar, DrawingCanvas.Tool.INITIAL_TRANSITION, "Initial", IconKind.INITIAL_TRANSITION);
                addToolButton(bottomBar, DrawingCanvas.Tool.FINAL_TRANSITION, "Final", IconKind.FINAL_TRANSITION);
                addToolButton(bottomBar, DrawingCanvas.Tool.AUTHORISATION, "Authorisation", IconKind.AUTHORISATION);
                break;
            case PROCESS_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.ROUNDED_RECTANGLE, "Process", IconKind.ROUND_RECT);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_FILLED, "Dataflow", IconKind.ARROW_FILLED);
                break;
            case OBJECT_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.OBJECT_TYPE, "Object Type", IconKind.OBJECT_TYPE);
                addToolButton(bottomBar, DrawingCanvas.Tool.REFERENCE, "Reference", IconKind.REFERENCE);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_DIAMOND, "Composition", IconKind.ARROW_DIAMOND);
                bottomBar.addSeparator();
                bottomBar.add(new JLabel("Reference:"));
                referenceSelector = new JComboBox<>(ReferenceKind.values());
                referenceSelector.setSelectedItem(ReferenceKind.PLAIN);
                canvas.setReferenceQualifier("");
                referenceSelector.addActionListener(e -> {
                    ReferenceKind kind = (ReferenceKind) referenceSelector.getSelectedItem();
                    canvas.setReferenceQualifier(kind != null ? kind.qualifier() : "");
                });
                bottomBar.add(referenceSelector);
                break;
        }

        JToggleButton active = toolButtons.get(canvas.getCurrentTool());
        if (active != null) {
            active.setSelected(true);
        } else if (selectBtn != null) {
            selectBtn.setSelected(true);
            canvas.setCurrentTool(DrawingCanvas.Tool.SELECT);
        }

        bottomBar.revalidate();
        bottomBar.repaint();
    }

    private void addToolButton(JToolBar bar, DrawingCanvas.Tool tool, String label, IconKind iconKind) {
        JToggleButton btn = new JToggleButton(label, new ToolIcon(iconKind));
        btn.addActionListener(e -> canvas.setCurrentTool(tool));
        toolGroup.add(btn);
        toolButtons.put(tool, btn);
        bar.add(btn);
    }

    private static class ToolIcon implements Icon {
        private final IconKind kind;
        private final int size = 18;

        ToolIcon(IconKind kind) {
            this.kind = kind;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(40, 40, 40));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            int pad = 3;
            int w = size - pad * 2;
            int h = size - pad * 2;
            int cx = x + pad;
            int cy = y + pad;

            switch (kind) {
                case SELECT:
                    // Cursor arrow icon
                    Polygon cursor = new Polygon();
                    cursor.addPoint(cx + 1, cy + 1);
                    cursor.addPoint(cx + 1, cy + h - 1);
                    cursor.addPoint(cx + 5, cy + h - 5);
                    cursor.addPoint(cx + 8, cy + h - 1);
                    cursor.addPoint(cx + 10, cy + h - 3);
                    cursor.addPoint(cx + 7, cy + h - 7);
                    cursor.addPoint(cx + w - 1, cy + h - 7);
                    g2.drawPolygon(cursor);
                    break;
                case TEXT:
                    g2.drawString("T", x + 6, y + 14);
                    break;
                case RECT:
                    g2.drawRect(cx, cy, w, h);
                    break;
                case OVAL:
                    g2.drawOval(cx, cy, w, h);
                    break;
                case ROUND_RECT:
                    g2.drawRoundRect(cx, cy, w, h, 6, 6);
                    break;
                case STATE:
                    g2.drawRoundRect(cx, cy + 3, w, h - 6, h - 6, h - 6);
                    break;
                case LINE:
                    g2.drawLine(cx, cy + h / 2, cx + w, cy + h / 2);
                    break;
                case ARROW_FILLED:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, true, false, false);
                    break;
                case ARROW_EMPTY:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, false, true, false);
                    break;
                case ARROW_DIAMOND:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, false, false, true);
                    break;
                case ARROW_OPEN:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, false, false, false);
                    break;
                case IMPACT:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, false, false, false);
                    break;
                case REFERENCE:
                    drawArrow(g2, cx, cy + h / 2, cx + w, cy + h / 2, false, false, false);
                    g2.drawLine(cx + 2, cy + 3, cx + w - 6, cy + 3);
                    break;
                case ENACTS:
                    g2.drawLine(cx, cy + h / 2, cx + w, cy + h / 2);
                    g2.fillOval(cx - 2, cy + h / 2 - 2, 5, 5);
                    break;
                case ACTOR:
                    g2.drawOval(cx + 5, cy, 6, 6);
                    g2.drawLine(cx + 8, cy + 6, cx + 8, cy + 12);
                    g2.drawLine(cx + 4, cy + 9, cx + 12, cy + 9);
                    g2.drawLine(cx + 8, cy + 12, cx + 4, cy + 16);
                    g2.drawLine(cx + 8, cy + 12, cx + 12, cy + 16);
                    break;
                case SYSTEM:
                    g2.drawRect(cx, cy, w, h);
                    g2.drawRect(cx + 3, cy + 3, w - 6, h - 6);
                    break;
                case BOUNDARY:
                    // Rectangle with tab at top-left
                    int tabW = 6;
                    int tabH = 4;
                    g2.drawRect(cx, cy + tabH, w, h - tabH);
                    g2.drawRect(cx, cy, tabW, tabH);
                    g2.drawLine(cx + tabW, cy + tabH, cx, cy + tabH);
                    break;
                case OBJECT_TYPE:
                    g2.drawRect(cx, cy, w, h);
                    g2.drawLine(cx, cy + 6, cx + w, cy + 6);
                    break;
                case AUTHORISATION:
                    Stroke oldStroke = g2.getStroke();
                    float[] dash = {4.0f, 2.0f};
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
                    g2.drawLine(cx, cy + h / 2, cx + w, cy + h / 2);
                    g2.setStroke(oldStroke);
                    break;
                case INITIAL_TRANSITION:
                    // Filled circle on left, arrow on right
                    g2.fillOval(cx, cy + h / 2 - 3, 6, 6);
                    g2.drawLine(cx + 6, cy + h / 2, cx + w - 4, cy + h / 2);
                    // Arrow head
                    g2.drawLine(cx + w, cy + h / 2, cx + w - 4, cy + h / 2 - 3);
                    g2.drawLine(cx + w, cy + h / 2, cx + w - 4, cy + h / 2 + 3);
                    break;
                case FINAL_TRANSITION:
                    // Line with arrow, circle with cross on right
                    g2.drawLine(cx, cy + h / 2, cx + w - 6, cy + h / 2);
                    // Arrow head
                    g2.drawLine(cx + w - 6, cy + h / 2, cx + w - 10, cy + h / 2 - 3);
                    g2.drawLine(cx + w - 6, cy + h / 2, cx + w - 10, cy + h / 2 + 3);
                    // Circle with cross
                    int circleR = 4;
                    g2.drawOval(cx + w - circleR, cy + h / 2 - circleR, circleR * 2, circleR * 2);
                    g2.drawLine(cx + w - 2, cy + h / 2 - 2, cx + w + 2, cy + h / 2 + 2);
                    g2.drawLine(cx + w - 2, cy + h / 2 + 2, cx + w + 2, cy + h / 2 - 2);
                    break;
                default:
                    g2.drawRect(cx, cy, w, h);
            }
            g2.dispose();
        }

        private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2, boolean filled, boolean empty, boolean diamond) {
            g2.drawLine(x1, y1, x2 - 4, y2);
            int hx = x2;
            int hy = y2;
            int size = 5;
            if (diamond) {
                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - size, hy - size);
                p.addPoint(hx - size * 2, hy);
                p.addPoint(hx - size, hy + size);
                if (filled) g2.fill(p);
                else g2.draw(p);
            } else if (empty) {
                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - size, hy - size);
                p.addPoint(hx - size, hy + size);
                g2.draw(p);
            } else if (filled) {
                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - size, hy - size);
                p.addPoint(hx - size, hy + size);
                g2.fill(p);
            } else {
                g2.drawLine(hx, hy, hx - size, hy - size);
                g2.drawLine(hx, hy, hx - size, hy + size);
            }
        }
    }
}
