package com.example.swingapp;

import javax.swing.*;
import java.awt.*;

public class MainFrame extends JFrame {
    private final DrawingCanvas canvas;
    private final ToolBarShell toolbar;

    public MainFrame() {
        super("Visual Editor App");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1500, 1200);
        setLocationRelativeTo(null);

        canvas = new DrawingCanvas();
        toolbar = new ToolBarShell(canvas);

        setLayout(new BorderLayout(6, 6));
        add(toolbar.getToolBar(), BorderLayout.NORTH);
        setJMenuBar(toolbar.createMenuBar(this));
        add(new JScrollPane(canvas), BorderLayout.CENTER);

        // optional status bar
        JLabel status = new JLabel("Ready");
        canvas.addStatusConsumer(status::setText);
        add(status, BorderLayout.SOUTH);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
