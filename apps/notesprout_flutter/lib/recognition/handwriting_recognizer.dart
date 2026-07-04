import 'package:flutter/foundation.dart';

import '../domain/stroke.dart';
import 'mlkit_recognizer.dart';

/// Returned when recognition fails or the model is unavailable (mirrors native FALLBACK_TEXT).
const kFallbackText = 'unrecognized';

/// General-purpose handwriting recognition — operates on raw stroke data with no knowledge of the
/// caller (heading vs text). Port of the native `recognition/HandwritingRecognizer.kt`.
///
/// Implementations: [MlKitRecognizer] (Android/BOOX + iPad) and [UnsupportedRecognizer] (desktop —
/// ML Kit has no desktop build, so the feature is simply absent there).
abstract class HandwritingRecognizer {
  /// Whether recognition exists on this platform at all (false on desktop → hide the UI).
  bool get supported;

  /// Whether the model is downloaded and the recognizer is ready to run now.
  bool get isReady;

  /// Download/init the model if needed (idempotent). Returns true when ready.
  Future<bool> ensureReady();

  /// Recognize the handwriting in [strokes], returning the top candidate or [kFallbackText].
  /// [preContext] chains the previously recognized text for accuracy (page pipeline, Stage 2).
  Future<String> recognize(List<StrokeData> strokes, {String preContext = ''});

  void dispose();
}

/// No-op recognizer for platforms without ML Kit (desktop/web).
class UnsupportedRecognizer implements HandwritingRecognizer {
  @override
  bool get supported => false;
  @override
  bool get isReady => false;
  @override
  Future<bool> ensureReady() async => false;
  @override
  Future<String> recognize(List<StrokeData> strokes, {String preContext = ''}) async =>
      kFallbackText;
  @override
  void dispose() {}
}

HandwritingRecognizer _create() {
  switch (defaultTargetPlatform) {
    case TargetPlatform.android:
    case TargetPlatform.iOS:
      return MlKitRecognizer();
    default:
      return UnsupportedRecognizer();
  }
}

/// App-level singleton holding the active recognizer (mirrors native HandwritingRecognizerProvider).
class Recognition {
  Recognition._();
  static HandwritingRecognizer? _instance;
  static HandwritingRecognizer get instance => _instance ??= _create();
}
