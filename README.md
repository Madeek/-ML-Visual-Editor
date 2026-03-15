# VisualEditor

VisualEditor is a Java Swing desktop application for creating and editing μML-inspired diagrams across multiple model views.

It supports interactive drawing, editing, model-aware tool palettes, and export to JSON, XML, and ReMoDeL model files.

## Features

- Multi-model editing modes:
  - Task Model
  - Impact Model
  - Object Model
  - State Model
  - Process Model
- Diagram tools for concepts and connectives (actors, tasks, objects, states, processes, associations, transitions, dataflows, etc.)
- Label editing for nodes and connectors
- Save and load full drawing state (.ser)
- Export model snapshots as:
  - .json
  - .xml
  - .remodel (model-instance export)
- Undo/redo support
- Copy/cut/paste support

## Tech Stack

- Java (module-based project)
- Swing/AWT UI
- In-memory domain model with event notifications

Module name: `VisualEditor`

## Project Structure

```text
src/
  VisualEditor/
    module-info.java
    com/example/swingapp/
      app/
        Main.java
        AppLauncher.java
      controller/
        MainController.java
      model/
        ReMoDeLEntity.java
        ReMoDeLModel.java
        ModelEvent.java
        ModelListener.java
        Concept.java
        Connective.java
        Feature.java
        Region.java
      view/
        MainFrame.java
        DrawingCanvas.java
        ToolBarShell.java
      service/
        ConnectionValidator.java
      persistence/
        ReMoDeLExporter.java

tools/
  pdf_text/
    Chapter 2 Core Elements.txt
    Chapter 3 Task Model.txt
    Chapter 4 Impact Model.txt
    Chapter 5 Object Model.txt
    Chapter 6 State Model.txt
    Chapter 7 Process Model.txt
```

## Requirements

- JDK 17 or later recommended
- macOS/Linux/Windows with a graphical environment

## Build

From the project root:

```bash
mkdir -p out
javac -d out --module-source-path src -m VisualEditor
```

## Run

After building:

```bash
java --module-path out -m VisualEditor/com.example.swingapp.app.Main
```

## Usage

1. Launch the application.
2. Choose the model type from the top toolbar.
3. Use the model-specific tool buttons to draw nodes and connectives.
4. Use the File menu to:
   - create a new diagram
   - save/open drawing snapshots (.ser)
   - export to .json, .xml, or .remodel

## Export Notes

- `.remodel` export writes model-instance output.
- Exported content is generated from the current in-memory diagram model.

## Architecture Notes

The app currently follows a pragmatic MVC-style split:

- App bootstrap: startup and UI launch
- Controller: assembles primary UI components and starts the app
- View: Swing components and interaction behavior
- Model: domain entities and evented model state
- Service: validation logic
- Persistence: file export logic

## Documentation Source

The `tools/pdf_text` folder contains extracted chapter text used as the domain reference for μML concepts and rules.

## Current Scope

This project focuses on interactive modeling and export workflows.

Some advanced chapter-level capabilities (for example, full rule enforcement and model-transformation pipelines between Task/Impact/Object/State/Process models) are not yet fully implemented in code.

## License

No license file is currently present in this repository.
