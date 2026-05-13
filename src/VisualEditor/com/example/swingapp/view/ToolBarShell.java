package com.example.swingapp.view;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.example.swingapp.model.ReMoDeLModel;
import com.example.swingapp.persistence.ReMoDeLExporter;

/**
 * Builds and coordinates the menu/toolbar controls for the drawing canvas.
 */
public class ToolBarShell {
    private final JPanel toolPanel;
    private final JToolBar toolBar;
    private final JToolBar bottomBar;
    private final DrawingCanvas canvas;
    private final Map<DrawingCanvas.Tool, JToggleButton> toolButtons = new HashMap<>();
    private ButtonGroup toolGroup = new ButtonGroup();
    private JComboBox<ModelType> modelSelector;
    private JComboBox<ReferenceKind> referenceSelector;
    private JComboBox<ImpactKind> impactSelector;
    private JComboBox<DataflowKind> dataflowSelector;
    private JComboBox<DrawingCanvas.ProcessActionKind> processActionSelector;
    private JSpinner objectTypeCountSpinner;
    private JLabel zoomValueLabel;
    private File currentDrawingFile;
    private ModelType activeModelType = ModelType.TASK_MODEL;
    private boolean suppressModelSelectorEvents = false;

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
        PAN,
        RECT,
        TASK,
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

    private enum ImpactKind {
        CREATE("create"),
        READ("read"),
        UPDATE("update"),
        DELETE("delete");

        private final String label;

        ImpactKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum DataflowKind {
        OBJECT("Object Dataflow", DrawingCanvas.DataflowKind.OBJECT),
        CONTENT("Content Dataflow", DrawingCanvas.DataflowKind.CONTENT),
        IDENTITY("Identity Dataflow", DrawingCanvas.DataflowKind.IDENTITY),
        GENERAL_OBJECT("General Object Dataflow", DrawingCanvas.DataflowKind.GENERAL_OBJECT);

        private final String label;
        private final DrawingCanvas.DataflowKind canvasKind;

        DataflowKind(String label, DrawingCanvas.DataflowKind canvasKind) {
            this.label = label;
            this.canvasKind = canvasKind;
        }

        public DrawingCanvas.DataflowKind canvasKind() {
            return canvasKind;
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
        canvas.setImpactLabel("create");
        canvas.setDataflowKind(DrawingCanvas.DataflowKind.OBJECT);


        JLabel modelLabel = new JLabel("Model:");
        modelSelector = new JComboBox<>(ModelType.values());
        modelSelector.setSelectedItem(ModelType.TASK_MODEL);
        modelSelector.addActionListener(e -> onModelSelectorChanged());
        toolBar.add(modelLabel);
        toolBar.add(modelSelector);


        JButton clearBtn = new JButton("Clear Canvas");
        clearBtn.setToolTipText("Remove all shapes from the canvas");
        clearBtn.addActionListener(e -> canvas.clear());
        toolBar.add(clearBtn);


        JToggleButton selectBtn = new JToggleButton("Select", new ToolIcon(IconKind.SELECT));
        selectBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.SELECT));
        toolGroup.add(selectBtn);
        toolButtons.put(DrawingCanvas.Tool.SELECT, selectBtn);
        selectBtn.setSelected(true);
        toolBar.add(selectBtn);



        JToggleButton panBtn = new JToggleButton("Pan", new ToolIcon(IconKind.PAN));
        panBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.PAN));
        toolGroup.add(panBtn);
        toolButtons.put(DrawingCanvas.Tool.PAN, panBtn);
        toolBar.add(panBtn);

        toolBar.addSeparator();
        toolBar.add(new JLabel("Zoom:"));

        JButton zoomOutBtn = new JButton("-");
        zoomOutBtn.setFocusable(false);
        zoomOutBtn.setToolTipText("Zoom out");
        zoomOutBtn.addActionListener(e -> canvas.zoomOut());
        toolBar.add(zoomOutBtn);

        JButton zoomInBtn = new JButton("+");
        zoomInBtn.setFocusable(false);
        zoomInBtn.setToolTipText("Zoom in");
        zoomInBtn.addActionListener(e -> canvas.zoomIn());
        toolBar.add(zoomInBtn);

        zoomValueLabel = new JLabel();
        zoomValueLabel.setToolTipText("Current zoom level");
        toolBar.add(zoomValueLabel);
        updateZoomLabel(canvas.getZoomScale());

        canvas.addPropertyChangeListener("zoomScale", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                Object value = evt.getNewValue();
                if (value instanceof Number) {
                    updateZoomLabel(((Number) value).doubleValue());
                }
            }
        });



        rebuildModelTools(ModelType.TASK_MODEL);


        canvas.addPropertyChangeListener("currentTool", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                Object nv = evt.getNewValue();
                if (nv instanceof DrawingCanvas.Tool) {
                    DrawingCanvas.Tool t = (DrawingCanvas.Tool) nv;
                    JToggleButton btn = toolButtons.get(t);
                    if (btn != null && !btn.isSelected()) btn.setSelected(true);


                    if (t == DrawingCanvas.Tool.SELECT) {
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    } else if (t == DrawingCanvas.Tool.PAN) {
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    } else {
                        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    }
                }
            }
        });
    }

    private void updateZoomLabel(double zoomScale) {
        if (zoomValueLabel == null) return;
        int percentage = (int) Math.round(zoomScale * 100.0);
        zoomValueLabel.setText(percentage + "%");
    }

    public JMenuBar createMenuBar(JFrame parentFrame) {
        JMenuBar menuBar = new JMenuBar();
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();


        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcutMask));
        newItem.addActionListener(e -> {

            canvas.clear();
            currentDrawingFile = null;
        });
        fileMenu.add(newItem);

        JMenuItem saveDrawingItem = new JMenuItem("Save");
        saveDrawingItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask));
        saveDrawingItem.addActionListener(e -> saveDrawing(parentFrame, false));
        fileMenu.add(saveDrawingItem);

        JMenuItem saveDrawingAsItem = new JMenuItem("Save As...");
        saveDrawingAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcutMask | InputEvent.SHIFT_DOWN_MASK));
        saveDrawingAsItem.addActionListener(e -> saveDrawing(parentFrame, true));
        fileMenu.add(saveDrawingAsItem);

        JMenuItem openDrawingItem = new JMenuItem("Open...");
        openDrawingItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask));
        openDrawingItem.addActionListener(e -> openDrawing(parentFrame));
        fileMenu.add(openDrawingItem);

        JMenuItem saveItem = new JMenuItem("Export...");
        saveItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            javax.swing.filechooser.FileNameExtensionFilter modFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("ReMoDeL models (*.mod)", "mod");
            javax.swing.filechooser.FileNameExtensionFilter xmlFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("XML files (*.xml)", "xml");
            javax.swing.filechooser.FileNameExtensionFilter jsonFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("JSON files (*.json)", "json");

            chooser.setFileFilter(modFilter);
            chooser.addChoosableFileFilter(xmlFilter);
            chooser.addChoosableFileFilter(jsonFilter);
            int res = chooser.showSaveDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File selected = chooser.getSelectedFile();
                String filePath = selected.getAbsolutePath();
                String lowerPath = filePath.toLowerCase();

                if (!lowerPath.endsWith(".json") && !lowerPath.endsWith(".xml")
                        && !lowerPath.endsWith(".mod") && !lowerPath.endsWith(".remodel")) {
                    javax.swing.filechooser.FileFilter chosenFilter = chooser.getFileFilter();
                    if (chosenFilter == jsonFilter) {
                        filePath = filePath + ".json";
                    } else if (chosenFilter == xmlFilter) {
                        filePath = filePath + ".xml";
                    } else {
                        filePath = filePath + ".mod";
                    }
                }
                try {

                    java.util.List<String> warnings = canvas.validateCompleteness(currentModelKind());
                    if (!warnings.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("The diagram may violate completeness rules:\n\n");
                        for (String w : warnings) {
                            sb.append("- ").append(w).append("\n");
                        }
                        sb.append("\nDo you want to continue with export?");
                        int choice = JOptionPane.showConfirmDialog(parentFrame, sb.toString(), "Export Warnings", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (choice != JOptionPane.YES_OPTION) {
                            return;
                        }
                    }

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


        JMenu editMenu = new JMenu("Edit");

        JMenuItem undoItem = new JMenuItem("Undo");


        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask));
        undoItem.addActionListener(e -> canvas.undo());
        undoItem.setEnabled(canvas.canUndo());
        editMenu.add(undoItem);

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcutMask));
        redoItem.addActionListener(e -> canvas.redo());
        redoItem.setEnabled(canvas.canRedo());
        editMenu.add(redoItem);

        editMenu.addSeparator();

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcutMask));
        selectAllItem.addActionListener(e -> canvas.selectAllShapes());
        editMenu.add(selectAllItem);

        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0));
        deleteItem.addActionListener(e -> canvas.deleteSelectedShape());
        editMenu.add(deleteItem);

        editMenu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask));
        copyItem.addActionListener(e -> canvas.copySelectedShape());
        editMenu.add(copyItem);

        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcutMask));
        cutItem.addActionListener(e -> canvas.cutSelectedShape());
        editMenu.add(cutItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask));
        pasteItem.addActionListener(e -> canvas.pasteClipboardShape());
        editMenu.add(pasteItem);


        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem showTools = new JCheckBoxMenuItem("Show Tools", true);
        showTools.addActionListener(e -> toolPanel.setVisible(showTools.isSelected()));
        viewMenu.add(showTools);


        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);


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

    private void saveDrawing(JFrame parentFrame, boolean forceSaveAs) {
        File target = currentDrawingFile;
        if (forceSaveAs || target == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Drawing files (*.ser)", "ser"));
            int res = chooser.showSaveDialog(parentFrame);
            if (res != JFileChooser.APPROVE_OPTION) return;
            target = chooser.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".ser")) {
                target = new File(target.getParentFile(), target.getName() + ".ser");
            }
        }

        try {
            canvas.setDocumentModelTypeName(activeModelType != null ? activeModelType.name() : ModelType.TASK_MODEL.name());
            canvas.saveDrawing(target);
            currentDrawingFile = target;
            JOptionPane.showMessageDialog(parentFrame, "Drawing saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parentFrame, "Error saving drawing: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openDrawing(JFrame parentFrame) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Drawing files (*.ser)", "ser"));
        int res = chooser.showOpenDialog(parentFrame);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        try {
            canvas.loadDrawing(file);
            applyLoadedModelContext(canvas.getLoadedDocumentModelTypeName());
            currentDrawingFile = file;
            JOptionPane.showMessageDialog(parentFrame, "Drawing loaded successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parentFrame, "Error loading drawing: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    public JComponent getToolBar() {
        return toolPanel;
    }

    private void onModelSelectorChanged() {
        if (suppressModelSelectorEvents) return;

        ModelType selected = (ModelType) modelSelector.getSelectedItem();
        if (selected == null || selected == activeModelType) return;

        if (canvas.hasDrawingContent()) {
            suppressModelSelectorEvents = true;
            modelSelector.setSelectedItem(activeModelType);
            suppressModelSelectorEvents = false;

            Window parent = SwingUtilities.getWindowAncestor(toolPanel);
            JOptionPane.showMessageDialog(
                parent,
                "You cannot change the model type while the canvas has a drawing.\n"
                    + "Use Clear or create a New file first.",
                "Model Type Locked",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        activeModelType = selected;
        canvas.setDocumentModelTypeName(activeModelType.name());
        rebuildModelTools(selected);
    }

    private void applyLoadedModelContext(String modelTypeName) {
        ModelType loadedType = null;
        if (modelTypeName != null && !modelTypeName.isBlank()) {
            try {
                loadedType = ModelType.valueOf(modelTypeName.trim());
            } catch (IllegalArgumentException ignored) {
                loadedType = null;
            }
        }
        if (loadedType == null) {
            loadedType = ModelType.TASK_MODEL;
        }

        activeModelType = loadedType;
        canvas.setDocumentModelTypeName(loadedType.name());
        suppressModelSelectorEvents = true;
        modelSelector.setSelectedItem(loadedType);
        suppressModelSelectorEvents = false;
        rebuildModelTools(loadedType);
    }

    private ReMoDeLExporter.ModelKind currentModelKind() {
        ModelType selected = activeModelType;
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
        toolButtons.keySet().removeIf(t -> t != DrawingCanvas.Tool.SELECT && t != DrawingCanvas.Tool.PAN && t != DrawingCanvas.Tool.TEXT);
        toolButtons.keySet().removeIf(t -> t != DrawingCanvas.Tool.SELECT && t != DrawingCanvas.Tool.PAN);
        toolGroup = new ButtonGroup();
        canvas.setDocumentModelTypeName(type != null ? type.name() : ModelType.TASK_MODEL.name());
        JToggleButton selectBtn = toolButtons.get(DrawingCanvas.Tool.SELECT);
        JToggleButton panBtn = toolButtons.get(DrawingCanvas.Tool.PAN);
        if (selectBtn != null) toolGroup.add(selectBtn);
        if (panBtn != null) toolGroup.add(panBtn);

        switch (type) {
            case TASK_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.TASK, "Task", IconKind.TASK);
                addToolButton(bottomBar, DrawingCanvas.Tool.ACTOR, "Actor", IconKind.ACTOR);
                addToolButton(bottomBar, DrawingCanvas.Tool.SYSTEM, "System", IconKind.SYSTEM);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.LINE, "Association", IconKind.LINE);
                addToolButton(bottomBar, DrawingCanvas.Tool.ENACTS, "Enacts", IconKind.ENACTS);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_DIAMOND, "Composition", IconKind.ARROW_DIAMOND);
                break;
            case IMPACT_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.TASK, "Task", IconKind.TASK);
                addToolButton(bottomBar, DrawingCanvas.Tool.OBJECT, "Object", IconKind.RECT);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                addToolButton(bottomBar, DrawingCanvas.Tool.IMPACT, "Impact", IconKind.IMPACT);
                impactSelector = new JComboBox<>(ImpactKind.values());
                impactSelector.setSelectedItem(ImpactKind.CREATE);
                canvas.setImpactLabel(ImpactKind.CREATE.label());
                impactSelector.addActionListener(e -> {
                    ImpactKind kind = (ImpactKind) impactSelector.getSelectedItem();
                    canvas.setImpactLabel(kind != null ? kind.label() : ImpactKind.CREATE.label());
                    canvas.setCurrentTool(DrawingCanvas.Tool.IMPACT);
                });
                bottomBar.add(impactSelector);
                break;
            case STATE_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.STATE, "State", IconKind.STATE);
                addToolButton(bottomBar, DrawingCanvas.Tool.ACTOR, "Actor", IconKind.ACTOR);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_OPEN, "Transition", IconKind.ARROW_OPEN);
                addToolButton(bottomBar, DrawingCanvas.Tool.INITIAL_TRANSITION, "Initial", IconKind.INITIAL_TRANSITION);
                addToolButton(bottomBar, DrawingCanvas.Tool.FINAL_TRANSITION, "Final", IconKind.FINAL_TRANSITION);
                addToolButton(bottomBar, DrawingCanvas.Tool.AUTHORISATION, "Authorisation", IconKind.AUTHORISATION);
                break;
            case PROCESS_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.PROCESS, "Process", IconKind.ROUND_RECT);
                addToolButton(bottomBar, DrawingCanvas.Tool.ACTION, "Action", IconKind.ROUND_RECT);
                processActionSelector = new JComboBox<>(DrawingCanvas.ProcessActionKind.values());
                processActionSelector.setSelectedItem(DrawingCanvas.ProcessActionKind.INPUT);
                canvas.setProcessActionKind(DrawingCanvas.ProcessActionKind.INPUT);
                processActionSelector.addActionListener(e -> {
                    DrawingCanvas.ProcessActionKind kind = (DrawingCanvas.ProcessActionKind) processActionSelector.getSelectedItem();
                    canvas.setProcessActionKind(kind != null ? kind : DrawingCanvas.ProcessActionKind.INPUT);
                    canvas.setCurrentTool(DrawingCanvas.Tool.ACTION);
                });
                bottomBar.add(processActionSelector);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_FILLED, "Dataflow", IconKind.ARROW_FILLED);
                dataflowSelector = new JComboBox<>(DataflowKind.values());
                dataflowSelector.setSelectedItem(DataflowKind.OBJECT);
                canvas.setDataflowKind(DataflowKind.OBJECT.canvasKind());
                dataflowSelector.addActionListener(e -> {
                    DataflowKind kind = (DataflowKind) dataflowSelector.getSelectedItem();
                    canvas.setDataflowKind(kind != null ? kind.canvasKind() : DataflowKind.OBJECT.canvasKind());
                    canvas.setCurrentTool(DrawingCanvas.Tool.ARROW_FILLED);
                });
                bottomBar.add(dataflowSelector);
                break;
            case OBJECT_MODEL:
                addToolButton(bottomBar, DrawingCanvas.Tool.OBJECT_TYPE, "Object Type", IconKind.OBJECT_TYPE);
                bottomBar.add(new JLabel("Attrs:"));
                objectTypeCountSpinner = new JSpinner(new SpinnerNumberModel(canvas.getObjectTypeAttributeCount(), 1, 50, 1));
                objectTypeCountSpinner.setToolTipText("Number of attributes to create for a new Object Type");
                ((JSpinner.DefaultEditor) objectTypeCountSpinner.getEditor()).getTextField().setColumns(3);
                objectTypeCountSpinner.addChangeListener(e -> {
                    Object value = objectTypeCountSpinner.getValue();
                    if (value instanceof Number) {
                        canvas.setObjectTypeAttributeCount(((Number) value).intValue());
                        canvas.setCurrentTool(DrawingCanvas.Tool.OBJECT_TYPE);
                    }
                });
                bottomBar.add(objectTypeCountSpinner);
                addToolButton(bottomBar, DrawingCanvas.Tool.BOUNDARY, "Boundary", IconKind.BOUNDARY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_EMPTY, "Generalisation", IconKind.ARROW_EMPTY);
                addToolButton(bottomBar, DrawingCanvas.Tool.ARROW_DIAMOND, "Composition", IconKind.ARROW_DIAMOND);
                bottomBar.addSeparator();
                addToolButton(bottomBar, DrawingCanvas.Tool.REFERENCE, "Reference", IconKind.REFERENCE);
                referenceSelector = new JComboBox<>(ReferenceKind.values());
                referenceSelector.setSelectedItem(ReferenceKind.PLAIN);
                canvas.setReferenceQualifier("");
                referenceSelector.addActionListener(e -> {
                    ReferenceKind kind = (ReferenceKind) referenceSelector.getSelectedItem();
                    canvas.setReferenceQualifier(kind != null ? kind.qualifier() : "");
                    canvas.setCurrentTool(DrawingCanvas.Tool.REFERENCE);
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
                    Polygon cursor = new Polygon();
                    cursor.addPoint(cx + 5, cy);
                    cursor.addPoint(cx, cy + 12);
                    cursor.addPoint(cx + 5, cy + 7);

                    cursor.addPoint(cx + 10, cy + 12);

                    g2.setColor(Color.WHITE);
                    g2.fill(cursor);
                    g2.setColor(new Color(40, 40, 40));
                    g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(cursor);
                    break;
                case PAN: {
                    Area hand = new Area(new RoundRectangle2D.Double(cx + 4, cy + 7, 8, 7, 3, 3));
                    hand.add(new Area(new RoundRectangle2D.Double(cx + 5, cy + 1, 2, 8, 2, 2)));
                    hand.add(new Area(new RoundRectangle2D.Double(cx + 8, cy + 1, 2, 9, 2, 2)));
                    hand.add(new Area(new RoundRectangle2D.Double(cx + 11, cy + 3, 2, 6, 2, 2)));
                    java.awt.Shape thumb = AffineTransform.getRotateInstance(-0.75, cx + 4, cy + 8)
                        .createTransformedShape(new RoundRectangle2D.Double(cx + 1, cy + 6, 2, 7, 2, 2));
                    hand.add(new Area(thumb));

                    g2.setColor(Color.WHITE);
                    g2.fill(hand);
                    g2.setColor(new Color(40, 40, 40));
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(hand);
                    break;
                }
                case RECT:
                    g2.drawRect(cx, cy, w, h);
                    break;
                case TASK:
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
                    g2.drawLine(cx, cy + h / 2, cx + w , cy + h / 2);
                    g2.fillOval(cx - 1, cy + h / 2 - 2, 5, 5);
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

                    g2.fillOval(cx, cy + h / 2 - 3, 6, 6);
                    g2.drawLine(cx + 6, cy + h / 2, cx + w - 4, cy + h / 2);

                    g2.drawLine(cx + w, cy + h / 2, cx + w - 4, cy + h / 2 - 3);
                    g2.drawLine(cx + w, cy + h / 2, cx + w - 4, cy + h / 2 + 3);
                    break;
                case FINAL_TRANSITION:

                    g2.drawLine(cx, cy + h / 2, cx + w - 6, cy + h / 2);

                    g2.drawLine(cx + w - 6, cy + h / 2, cx + w - 10, cy + h / 2 - 3);
                    g2.drawLine(cx + w - 6, cy + h / 2, cx + w - 10, cy + h / 2 + 3);

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
            int hx = x2;
            int hy = y2;
            int size = 5;
            if (diamond) {
                int len = 8;
                int half = 4;
                int rearX = hx - len;
                g2.drawLine(x1, y1, rearX, y2);

                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - half, hy - half);
                p.addPoint(rearX, hy);
                p.addPoint(hx - half, hy + half);
                if (filled) g2.fill(p);
                else g2.draw(p);
            } else if (empty) {
                g2.drawLine(x1, y1, x2 - 4, y2);
                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - size, hy - size);
                p.addPoint(hx - size, hy + size);
                g2.draw(p);
            } else if (filled) {
                g2.drawLine(x1, y1, x2 - 4, y2);
                Polygon p = new Polygon();
                p.addPoint(hx, hy);
                p.addPoint(hx - size, hy - size);
                p.addPoint(hx - size, hy + size);
                g2.fill(p);
            } else {
                g2.drawLine(x1, y1, x2 - 4, y2);
                g2.drawLine(hx, hy, hx - size, hy - size);
                g2.drawLine(hx, hy, hx - size, hy + size);
            }
        }
    }
}
