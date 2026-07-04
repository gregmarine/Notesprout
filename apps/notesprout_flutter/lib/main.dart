import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'data/app_settings.dart';
import 'data/db_worker.dart';
import 'recognition/handwriting_recognizer.dart';
import 'ui/library_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // On Android use the app-external files dir (== getExternalFilesDir(null)) so .soil files can be
  // side-loaded via adb; on desktop use the per-user app-support dir. Either way notesprout.db +
  // Garden/ live under [base].
  final dir = Platform.isAndroid
      ? await getExternalStorageDirectory()
      : await getApplicationSupportDirectory();
  final base = dir!.path;
  await AppSettings.load('$base/settings.json'); // device-local prefs (snap toggle, …)
  final worker = await DbWorker.start(indexPath: '$base/notesprout.db', gardenDir: '$base/Garden');
  // Kick the one-time ML Kit model download early (mobile only; no-op on desktop) so the convert
  // tools are ready by the time the user reaches for them. Fire-and-forget; recognize() re-awaits it.
  Recognition.instance.ensureReady();
  runApp(NotesproutApp(worker: worker));
}

class NotesproutApp extends StatelessWidget {
  const NotesproutApp({super.key, required this.worker});

  final DbWorker worker;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Notesprout',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        scaffoldBackgroundColor: Colors.white,
        splashFactory: NoSplash.splashFactory,
        highlightColor: Colors.transparent,
      ),
      home: LibraryScreen(worker: worker),
    );
  }
}
