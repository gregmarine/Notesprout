import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/core/undo_manager.dart';

void main() {
  test('undo/redo apply the right closures and gate on availability', () {
    final log = <String>[];
    final stack = UndoStack();

    expect(stack.canUndo, isFalse);
    expect(stack.canRedo, isFalse);
    stack.undo(); // no-op
    stack.redo(); // no-op
    expect(log, isEmpty);

    stack.push(UndoAction(undo: () => log.add('u1'), redo: () => log.add('r1')));
    stack.push(UndoAction(undo: () => log.add('u2'), redo: () => log.add('r2')));

    expect(stack.canUndo, isTrue);
    stack.undo(); // undoes the most recent
    expect(log, ['u2']);
    expect(stack.canRedo, isTrue);
    stack.redo();
    expect(log, ['u2', 'r2']);

    // A new action after an undo clears the redo stack.
    stack.undo(); // u2
    stack.push(UndoAction(undo: () => log.add('u3'), redo: () => log.add('r3')));
    expect(stack.canRedo, isFalse);
  });

  test('clear empties both stacks', () {
    final stack = UndoStack()
      ..push(UndoAction(undo: () {}, redo: () {}));
    stack.clear();
    expect(stack.canUndo, isFalse);
    expect(stack.canRedo, isFalse);
  });
}
