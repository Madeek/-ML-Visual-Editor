package com.example.swingapp.service;

import com.example.swingapp.view.DrawingCanvas;


/**
 * Centralized rule set for validating connector types by model context.
 */
public class ConnectionValidator {

    public enum ModelType {
        TASK_MODEL,      // Task & State nodes with Precedence/Transition arcs
        OBJECT_MODEL,    // Object nodes with Association/Composition/Generalization arcs
        PROCESS_MODEL    // Process/Task nodes with Data Flow/Association arcs
    }


    /**
     * Returns true when the requested connector is allowed for the given model.
     */
    public static boolean isValidConnection(String sourceType, String targetType, DrawingCanvas.Tool arcType, ModelType modelType) {
        if (sourceType == null || targetType == null || arcType == null) return false;

        switch (modelType) {
            case TASK_MODEL:
                return isValidTaskConnection(sourceType, targetType, arcType);
            case OBJECT_MODEL:
                return isValidObjectConnection(sourceType, targetType, arcType);
            case PROCESS_MODEL:
                return isValidProcessConnection(sourceType, targetType, arcType);
            default:
                return true; // allow all by default
        }
    }

    private static boolean isValidTaskConnection(String source, String target, DrawingCanvas.Tool arc) {

        boolean isTaskOrState = source.equalsIgnoreCase("Task") || source.equalsIgnoreCase("State")
                             || target.equalsIgnoreCase("Task") || target.equalsIgnoreCase("State");
        
        if (!isTaskOrState) return false;


        switch (arc) {
            case ARROW_OPEN:          // Association (generic precedence)
            case ARROW_EMPTY:         // Generalization (refinement of task)
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidObjectConnection(String source, String target, DrawingCanvas.Tool arc) {

        boolean isObject = source.equalsIgnoreCase("Object") && target.equalsIgnoreCase("Object");
        
        if (!isObject) return false;


        switch (arc) {
            case ARROW_OPEN:          // Association
            case ARROW_DIAMOND:       // Composition
            case ARROW_EMPTY:         // Generalization
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidProcessConnection(String source, String target, DrawingCanvas.Tool arc) {

        boolean isProcessOrTask = (source.equalsIgnoreCase("Process") || source.equalsIgnoreCase("Task"))
                               && (target.equalsIgnoreCase("Process") || target.equalsIgnoreCase("Task"));
        
        if (!isProcessOrTask) return false;


        switch (arc) {
            case ARROW_FILLED:        // Data Flow
            case ARROW_OPEN:          // Association
                return true;
            default:
                return false;
        }
    }
}
