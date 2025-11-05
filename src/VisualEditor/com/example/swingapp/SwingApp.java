// filepath: /Users/mannie/eclipse-workspace/VisualEditor/src/com/example/swingapp/SwingApp.java
package com.example.swingapp;

import javax.swing.*;

public class SwingApp {
    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
