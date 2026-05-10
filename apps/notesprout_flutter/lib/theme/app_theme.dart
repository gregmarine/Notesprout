import 'package:flutter/material.dart';

class AppTheme {
  AppTheme._();

  static const Color inkBlack = Color(0xFF000000);
  static const Color paperWhite = Color(0xFFFFFFFF);
  static const Color inkLight = Color(0xFF888888);
  static const Color borderGray = Color(0xFFCCCCCC);

  static const double _radius = 4.0;
  static const BorderRadius _borderRadius = BorderRadius.all(Radius.circular(_radius));
  static const BorderSide _borderBlack = BorderSide(color: inkBlack, width: 1.0);
  static const BorderSide _borderGray = BorderSide(color: borderGray, width: 1.0);

  static ThemeData get themeData => ThemeData(
        useMaterial3: false,
        scaffoldBackgroundColor: paperWhite,
        canvasColor: paperWhite,
        splashFactory: NoSplash.splashFactory,
        highlightColor: Colors.transparent,
        splashColor: Colors.transparent,
        hoverColor: Colors.transparent,
        focusColor: Colors.transparent,

        colorScheme: const ColorScheme.light(
          primary: inkBlack,
          onPrimary: paperWhite,
          secondary: inkBlack,
          onSecondary: paperWhite,
          surface: paperWhite,
          onSurface: inkBlack,
          error: inkBlack,
          onError: paperWhite,
        ),

        appBarTheme: const AppBarTheme(
          backgroundColor: paperWhite,
          foregroundColor: inkBlack,
          elevation: 0,
          shadowColor: Colors.transparent,
          titleTextStyle: TextStyle(
            color: inkBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
          iconTheme: IconThemeData(color: inkBlack),
          shape: Border(bottom: _borderGray),
        ),

        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ButtonStyle(
            backgroundColor: WidgetStatePropertyAll(inkBlack),
            foregroundColor: WidgetStatePropertyAll(paperWhite),
            overlayColor: WidgetStatePropertyAll(Colors.transparent),
            shadowColor: WidgetStatePropertyAll(Colors.transparent),
            elevation: WidgetStatePropertyAll(0),
            shape: WidgetStatePropertyAll(
              RoundedRectangleBorder(borderRadius: _borderRadius),
            ),
            textStyle: WidgetStatePropertyAll(
              TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
            ),
          ),
        ),

        outlinedButtonTheme: OutlinedButtonThemeData(
          style: ButtonStyle(
            backgroundColor: WidgetStatePropertyAll(paperWhite),
            foregroundColor: WidgetStatePropertyAll(inkBlack),
            overlayColor: WidgetStatePropertyAll(Colors.transparent),
            shadowColor: WidgetStatePropertyAll(Colors.transparent),
            elevation: WidgetStatePropertyAll(0),
            side: WidgetStatePropertyAll(_borderBlack),
            shape: WidgetStatePropertyAll(
              RoundedRectangleBorder(borderRadius: _borderRadius),
            ),
            textStyle: WidgetStatePropertyAll(
              TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
            ),
          ),
        ),

        textButtonTheme: TextButtonThemeData(
          style: ButtonStyle(
            foregroundColor: WidgetStatePropertyAll(inkBlack),
            overlayColor: WidgetStatePropertyAll(Colors.transparent),
            shadowColor: WidgetStatePropertyAll(Colors.transparent),
            textStyle: WidgetStatePropertyAll(
              TextStyle(fontSize: 14, fontWeight: FontWeight.w500, decoration: TextDecoration.none),
            ),
          ),
        ),

        inputDecorationTheme: const InputDecorationTheme(
          filled: false,
          border: OutlineInputBorder(
            borderRadius: _borderRadius,
            borderSide: _borderBlack,
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: _borderRadius,
            borderSide: _borderBlack,
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: _borderRadius,
            borderSide: _borderBlack,
          ),
          errorBorder: OutlineInputBorder(
            borderRadius: _borderRadius,
            borderSide: _borderBlack,
          ),
          focusedErrorBorder: OutlineInputBorder(
            borderRadius: _borderRadius,
            borderSide: _borderBlack,
          ),
          labelStyle: TextStyle(color: inkLight),
          hintStyle: TextStyle(color: inkLight),
          contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        ),

        dividerTheme: const DividerThemeData(
          color: inkBlack,
          thickness: 1,
          space: 1,
        ),

        cardTheme: CardThemeData(
          color: paperWhite,
          elevation: 0,
          shadowColor: Colors.transparent,
          shape: RoundedRectangleBorder(
            borderRadius: _borderRadius,
            side: _borderBlack,
          ),
          margin: EdgeInsets.zero,
        ),

        pageTransitionsTheme: const PageTransitionsTheme(
          builders: {
            TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
            TargetPlatform.iOS: FadeUpwardsPageTransitionsBuilder(),
            TargetPlatform.macOS: FadeUpwardsPageTransitionsBuilder(),
            TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
            TargetPlatform.linux: FadeUpwardsPageTransitionsBuilder(),
            TargetPlatform.fuchsia: FadeUpwardsPageTransitionsBuilder(),
          },
        ),

        textTheme: const TextTheme(
          displayLarge: TextStyle(fontSize: 57, fontWeight: FontWeight.w300, color: inkBlack, letterSpacing: -0.25),
          displayMedium: TextStyle(fontSize: 45, fontWeight: FontWeight.w300, color: inkBlack),
          displaySmall: TextStyle(fontSize: 36, fontWeight: FontWeight.w300, color: inkBlack),
          headlineLarge: TextStyle(fontSize: 32, fontWeight: FontWeight.w400, color: inkBlack),
          headlineMedium: TextStyle(fontSize: 28, fontWeight: FontWeight.w400, color: inkBlack),
          headlineSmall: TextStyle(fontSize: 24, fontWeight: FontWeight.w400, color: inkBlack),
          titleLarge: TextStyle(fontSize: 22, fontWeight: FontWeight.w600, color: inkBlack),
          titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: inkBlack),
          titleSmall: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: inkBlack),
          bodyLarge: TextStyle(fontSize: 16, fontWeight: FontWeight.w400, color: inkBlack),
          bodyMedium: TextStyle(fontSize: 14, fontWeight: FontWeight.w400, color: inkBlack),
          bodySmall: TextStyle(fontSize: 12, fontWeight: FontWeight.w400, color: inkLight),
          labelLarge: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: inkBlack),
          labelMedium: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: inkBlack),
          labelSmall: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: inkLight),
        ),
      );
}
