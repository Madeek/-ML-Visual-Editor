package com.example.swingapp.controller;

import com.example.swingapp.view.DrawingCanvas;
import com.example.swingapp.view.MainFrame;
import com.example.swingapp.view.ToolBarShell;

public class MainController {
    private final DrawingCanvas canvas;
    private final ToolBarShell toolbar;
    private final MainFrame frame;

    public MainController() {
        this.canvas = new DrawingCanvas();
        this.toolbar = new ToolBarShell(canvas);
        this.frame = new MainFrame(canvas, toolbar);
    }

    public void start() {
        frame.setVisible(true);
    }

    public MainFrame getFrame() {
        return frame;
    }
}
