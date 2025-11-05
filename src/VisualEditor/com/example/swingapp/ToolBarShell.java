package com.example.swingapp;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class ToolBarShell {
    private final JPanel toolPanel;
    private final JToolBar toolBar;
    private final JToolBar bottomBar;
    private final DrawingCanvas canvas;
    private final Map<DrawingCanvas.Tool, JToggleButton> toolButtons = new HashMap<>();
    private final ButtonGroup toolGroup = new ButtonGroup();

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
        JToggleButton selectBtn = new JToggleButton("Select");
        selectBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.SELECT));
        toolGroup.add(selectBtn);
        toolButtons.put(DrawingCanvas.Tool.SELECT, selectBtn);
        selectBtn.setSelected(true);
        toolBar.add(selectBtn);

        // Textbox tool
        JToggleButton textBtn = new JToggleButton("Text");
        textBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.TEXT));
        toolGroup.add(textBtn);
        toolButtons.put(DrawingCanvas.Tool.TEXT, textBtn);
        toolBar.add(textBtn);

        // Object drawing tools
        // Rectangle button
       JToggleButton rectBtn = new JToggleButton("Rectangle");
       rectBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.RECTANGLE));
       toolGroup.add(rectBtn);
       toolButtons.put(DrawingCanvas.Tool.RECTANGLE, rectBtn);
       bottomBar.add(rectBtn);

        // Oval button
        JToggleButton ovalBtn = new JToggleButton("Oval");
        ovalBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.OVAL));
        toolGroup.add(ovalBtn);
        toolButtons.put(DrawingCanvas.Tool.OVAL, ovalBtn);
        bottomBar.add(ovalBtn);

        // Rounded Rectangle button
        JToggleButton roundRectBtn = new JToggleButton("Rounded Rect");
        roundRectBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.ROUNDED_RECTANGLE));
        toolGroup.add(roundRectBtn);
        toolButtons.put(DrawingCanvas.Tool.ROUNDED_RECTANGLE, roundRectBtn);
        bottomBar.add(roundRectBtn);

        // Line button
        JToggleButton lineBtn = new JToggleButton("Line");
        lineBtn.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.LINE));
        toolGroup.add(lineBtn);
        toolButtons.put(DrawingCanvas.Tool.LINE, lineBtn);
        bottomBar.add(lineBtn);

        // Arrows
        // Filled arrow
        JToggleButton arrowFilled = new JToggleButton("Arrow (filled)");
        arrowFilled.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.ARROW_FILLED));
        toolGroup.add(arrowFilled);
        toolButtons.put(DrawingCanvas.Tool.ARROW_FILLED, arrowFilled);
        bottomBar.add(arrowFilled);

        // Diamond arrow
        JToggleButton arrowDiamond = new JToggleButton("Arrow (diamond)");
        arrowDiamond.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.ARROW_DIAMOND));
        toolGroup.add(arrowDiamond);
        toolButtons.put(DrawingCanvas.Tool.ARROW_DIAMOND, arrowDiamond);
        bottomBar.add(arrowDiamond);

        // Open arrow
        JToggleButton arrowOpen = new JToggleButton("Arrow (open)");
        arrowOpen.addActionListener(e -> canvas.setCurrentTool(DrawingCanvas.Tool.ARROW_OPEN));
        toolGroup.add(arrowOpen);
        toolButtons.put(DrawingCanvas.Tool.ARROW_OPEN, arrowOpen);
        bottomBar.add(arrowOpen);

        // Stroke size slider
        // bottomBar.addSeparator();
        // bottomBar.add(new JLabel("Stroke: "));
        // JSlider stroke = new JSlider(JSlider.HORIZONTAL, 1, 10, 3);
        // stroke.setPreferredSize(new Dimension(30, 20));
        // stroke.setMaximumSize(new Dimension(250, 20));
        // stroke.setMinimumSize(new Dimension(10, 20));
        // stroke.addChangeListener(e -> canvas.setStrokeWidth(stroke.getValue()));
        // bottomBar.add(stroke);

        // listen for canvas tool changes so toolbar highlights stay in sync
        canvas.addPropertyChangeListener("currentTool", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                Object nv = evt.getNewValue();
                if (nv instanceof DrawingCanvas.Tool) {
                    DrawingCanvas.Tool t = (DrawingCanvas.Tool) nv;
                    JToggleButton btn = toolButtons.get(t);
                    if (btn != null && !btn.isSelected()) btn.setSelected(true);
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

        JMenuItem openItem = new JMenuItem("Open...");
        openItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int res = chooser.showOpenDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                // TODO: load file into canvas / model
            }
        });
        fileMenu.add(openItem);

        JMenuItem saveItem = new JMenuItem("Save...");
        saveItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int res = chooser.showSaveDialog(parentFrame);
            if (res == JFileChooser.APPROVE_OPTION) {
                // TODO: save model/canvas to disk
            }
        });
        fileMenu.add(saveItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
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
        redoItem.addActionListener(e -> {
            try {
                java.lang.reflect.Method m = canvas.getClass().getMethod("redo");
                m.invoke(canvas);
            } catch (Exception ex) {
                // ignore if redo not available
            }
        });
        editMenu.add(redoItem);

        // --- View menu
        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem showTools = new JCheckBoxMenuItem("Show Tools", true);
        showTools.addActionListener(e -> toolPanel.setVisible(showTools.isSelected()));
        viewMenu.add(showTools);

        // Add menus to bar
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);

        return menuBar;
    }
    
    public JComponent getToolBar() {
        return toolPanel;
    }
}
