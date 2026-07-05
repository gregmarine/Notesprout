import 'package:google_mlkit_digital_ink_recognition/google_mlkit_digital_ink_recognition.dart'
    as mlkit;

import '../domain/stroke.dart';
import 'handwriting_recognizer.dart';

/// ML Kit Digital Ink implementation — same engine as the native app
/// (`recognition/MlKitHandwritingRecognizer.kt`): stroke-based, fully on-device after a one-time
/// model download, no ink ever leaves the device.
class MlKitRecognizer implements HandwritingRecognizer {
  static const _lang = 'en-US';
  static const _maxPreContext = 20; // Google's stated optimum; more adds latency, no benefit

  final _manager = mlkit.DigitalInkRecognizerModelManager();
  mlkit.DigitalInkRecognizer? _recognizer;
  bool _ready = false;
  Future<bool>? _init;

  @override
  bool get supported => true;

  @override
  bool get isReady => _ready;

  @override
  Future<bool> ensureReady() => _init ??= _initModel();

  Future<bool> _initModel() async {
    try {
      if (!await _manager.isModelDownloaded(_lang)) {
        // Any connection allowed (matches native DownloadConditions default), ~20 MB one-time.
        final ok = await _manager.downloadModel(_lang, isWifiRequired: false);
        if (!ok) {
          _init = null; // let a later call retry (e.g. once the device has connectivity)
          return false;
        }
      }
      _recognizer = mlkit.DigitalInkRecognizer(languageCode: _lang);
      _ready = true;
      return true;
    } catch (_) {
      _init = null; // retryable
      return false;
    }
  }

  @override
  Future<String> recognize(List<StrokeData> strokes, {String preContext = ''}) async {
    if (!await ensureReady()) return kFallbackText;
    final r = _recognizer;
    if (r == null || strokes.isEmpty) return kFallbackText;

    final ink = mlkit.Ink();
    var t = 0; // synthetic monotonic timestamps (ML Kit needs ordering, not real time)
    double? minX, minY, maxX, maxY;
    for (final s in strokes) {
      final stroke = mlkit.Stroke();
      for (final p in s.points) {
        stroke.points.add(mlkit.StrokePoint(x: p.x, y: p.y, t: t++));
        minX = (minX == null || p.x < minX) ? p.x : minX;
        minY = (minY == null || p.y < minY) ? p.y : minY;
        maxX = (maxX == null || p.x > maxX) ? p.x : maxX;
        maxY = (maxY == null || p.y > maxY) ? p.y : maxY;
      }
      if (stroke.points.isNotEmpty) ink.strokes.add(stroke);
    }
    if (ink.strokes.isEmpty || minX == null) return kFallbackText;

    final w = (maxX! - minX).clamp(1.0, double.infinity);
    final h = (maxY! - minY!).clamp(1.0, double.infinity);
    final pre = preContext.length > _maxPreContext
        ? preContext.substring(preContext.length - _maxPreContext)
        : preContext;
    final ctx = mlkit.DigitalInkRecognitionContext(
      preContext: pre,
      writingArea: mlkit.WritingArea(width: w, height: h),
    );

    try {
      final candidates = await r.recognize(ink, context: ctx);
      final text = candidates.isEmpty ? '' : candidates.first.text.trim();
      return text.isEmpty ? kFallbackText : text;
    } catch (_) {
      return kFallbackText;
    }
  }

  @override
  void dispose() {
    _recognizer?.close();
    _recognizer = null;
    _ready = false;
  }
}
