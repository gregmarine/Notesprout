import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'data/db_worker.dart';
import 'ui/library_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Android app-external files dir == getExternalFilesDir(null): notesprout.db + Garden/ live here.
  final dir = await getExternalStorageDirectory();
  final base = dir!.path;
  final worker = await DbWorker.start(indexPath: '$base/notesprout.db', gardenDir: '$base/Garden');
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
