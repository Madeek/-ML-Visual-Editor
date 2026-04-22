package com.example.swingapp.view;

import java.awt.*;
import java.util.Objects;
import javax.swing.*;

public class MainFrame extends JFrame {
    private final DrawingCanvas canvas;
    private final ToolBarShell toolbar;

    public MainFrame(DrawingCanvas canvas, ToolBarShell toolbar) {
        super("µML Editor");
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.toolbar = Objects.requireNonNull(toolbar, "toolbar");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1500, 1200);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout(6, 6));
        add(toolbar.getToolBar(), BorderLayout.NORTH);
        setJMenuBar(toolbar.createMenuBar(this));
        add(new JScrollPane(canvas), BorderLayout.CENTER);

        // optional status bar
        JLabel status = new JLabel("Ready");
        canvas.addStatusConsumer(status::setText);
        add(status, BorderLayout.SOUTH);
    }
}
