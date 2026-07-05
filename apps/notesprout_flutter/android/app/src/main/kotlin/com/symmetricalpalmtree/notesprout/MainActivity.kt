package com.symmetricalpalmtree.notesprout

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "notesprout/onyx_drawing",
            OnyxDrawingPlatformViewFactory(messenger),
        )
        // A second, independent overlay for the sticky-note content editor. It gets its OWN channel
        // name so opening/closing it never touches the notebook's `notesprout/onyx` handler (a shared
        // name would orphan the notebook's handler when the editor's view disposes).
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "notesprout/onyx_sticky_drawing",
            OnyxDrawingPlatformViewFactory(messenger, "notesprout/onyx_sticky"),
        )
    }
}
