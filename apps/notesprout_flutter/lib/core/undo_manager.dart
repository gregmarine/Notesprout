/// A single reversible edit. [undo] restores the prior state; [redo] re-applies the edit. Both are
/// plain closures that mutate the host screen's in-memory model and fire the matching background
/// `.soil` write — keeping undo/redo symmetric with the original action.
class UndoAction {
  const UndoAction({required this.undo, required this.redo});
  final void Function() undo;
  final void Function() redo;
}

/// In-memory undo/redo stack (session-scoped; persistence to `undo_redo_state` is deferred). A new
/// action clears the redo stack, matching the native app's single-timeline model.
/// (Named `UndoStack`, not `UndoManager`, to avoid clashing with Flutter's `services.UndoManager`.)
class UndoStack {
  final _undo = <UndoAction>[];
  final _redo = <UndoAction>[];

  bool get canUndo => _undo.isNotEmpty;
  bool get canRedo => _redo.isNotEmpty;

  /// Record an already-applied action.
  void push(UndoAction action) {
    _undo.add(action);
    _redo.clear();
  }

  void undo() {
    if (_undo.isEmpty) return;
    final a = _undo.removeLast();
    a.undo();
    _redo.add(a);
  }

  void redo() {
    if (_redo.isEmpty) return;
    final a = _redo.removeLast();
    a.redo();
    _undo.add(a);
  }

  void clear() {
    _undo.clear();
    _redo.clear();
  }
}
