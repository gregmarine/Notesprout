package com.symmetricalpalmtree.notesprout

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "notesprout/onyx_drawing",
            OnyxDrawingPlatformViewFactory(flutterEngine.dartExecutor.binaryMessenger),
        )
    }
}
