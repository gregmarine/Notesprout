package com.notesprout.notesprout_flutter

import com.notesprout.app.OnyxCanvasMethodChannel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        OnyxCanvasMethodChannel(flutterEngine, this)
    }
}
