package com.example.swingapp.app;

import com.example.swingapp.controller.MainController;
import javax.swing.SwingUtilities;

public final class AppLauncher {
    private AppLauncher() {
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            MainController controller = new MainController();
            controller.start();
        });
    }
}
