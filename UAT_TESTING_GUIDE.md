# User Acceptance Testing Guide

This guide is for the testers of my VisualEditor project. Each tester should focus on the model assigned to them and report what works, what feels confusing, and any bugs or unexpected behavior. 

- If you're feeling up to it feel free to test more than one model.

## General Setup for All Testers

1. Launch VisualEditor.
2. Choose your assigned model from the top model dropdown.
3. Use the tools shown for that model only.
4. Create a small diagram from scratch.
5. Save the drawing, reload it, and confirm it looks the same.
6. Try one export if your model supports it.
7. Report any issues with labels, connectors, selection, dragging, zooming, saving, or exporting.

When reporting a bug, please include:

- What you clicked or dragged
- What you expected to happen
- What actually happened
- A screenshot if possible
- Whether it happened every time or only once

### Tester Instructions

- Download the release for your assigned model.
- Run the app and complete the tasks in your assigned model section.
- Record completion status, ease of use, observed errors, and qualitative feedback.
- If something fails, include the steps to reproduce and a screenshot if possible.
- Submit your results through the chosen GitHub feedback channel.

## Task Model Tester

### What to test

- Create tasks and actors.
- Draw associations and any other available task-model connectors.
- Move items around and confirm connectors stay attached correctly.
- Try selecting, deleting, copying, and pasting items.
- Use zoom in and zoom out.

### What should feel correct

- The task tools should match task modeling behavior.
- Connectors should attach cleanly to shapes.
- Labels should remain readable after editing and zooming.

### Watch for

- Connectors snapping to the wrong shape
- Bad arrow placement
- Labels that overlap shapes or disappear after moving

## Impact Model Tester

### What to test

- Create tasks and objects.
- Add Impact connectors.
- Use the Impact dropdown and verify the label changes correctly.
- Check the available impact options: `create`, `read`, `update`, `delete`.
- Confirm the default is `create` when you first add an Impact connector.

### What should feel correct

- The Impact selector should behave like a model-level dropdown.
- The selected value should show up on the connector.
- Saving and reloading should preserve the label.

### Watch for

- Wrong default value
- A mismatch between the dropdown choice and the connector label
- Exported output not preserving the selected impact kind

## Object Model Tester

### What to test

- Create object types.
- Add references, generalisations, and compositions.
- Use the reference dropdown options and verify the qualifier updates correctly.
- Edit object type labels and confirm the text layout stays usable.
- Add enough attributes to see how the object type box grows.

### What should feel correct

- Reference labels should match the selected reference kind.
- Object type shapes should stay readable after editing.
- Connector labels should export and reload correctly.

### Watch for

- Broken multi-line labels
- Qualifier text not matching the selected reference kind
- Attribute editing issues

## State Model Tester

### What to test

- Create states and actors.
- Add transitions, initial transitions, final transitions, and authorisation connectors if available.
- Edit transition labels and check the event, guard, and action fields.
- Test self-loop transitions if your diagram needs them.
- Move states and verify transition geometry remains sensible.

### What should feel correct

- Transition labels should be easy to edit and read.
- The label format should stay consistent after saving and reloading.
- Connectors should track moved states without breaking.

### Watch for

- Missing arrowheads
- Incorrect transition label formatting
- Self-loop connectors drawing in the wrong place

## Process Model Tester

### What to test

- Create processes and the available process-model elements.
- Add dataflow connectors.
- Use the dataflow dropdown and try the available kinds:
  - Object Dataflow
  - Content Dataflow
  - Identity Dataflow
  - General Object Dataflow
- For object dataflows, confirm the label is the object name.
- For content dataflows, confirm the label uses `{val}`.
- For identity dataflows, confirm the label uses `{id}`.
- For general object dataflows, confirm the label includes the general object name plus subtype text in parentheses.

### What should feel correct

- The dataflow selector should behave like a model-level dropdown.
- The selected kind should be reflected in the connector label.
- The label should still look correct after saving, reopening, and exporting.

### Watch for

- Incorrect `{id}` or `{val}` formatting
- Subtype text not appearing in the general object case
- Dataflow labels changing unexpectedly after reload

## Conclusion

Thanks for helping me test out this system, I really appreciate you taking out time to do this. Don't forget to fill in the [feedback form](https://forms.gle/vGqCR1LFDEuaPD5B7)
