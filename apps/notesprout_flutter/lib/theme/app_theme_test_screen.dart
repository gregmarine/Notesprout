import 'package:flutter/material.dart';
import 'app_theme.dart';

class AppThemeTestScreen extends StatelessWidget {
  const AppThemeTestScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Theme QA')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Section('Typography', [
              Text('Display Large', style: tt.displayLarge),
              Text('Headline Large', style: tt.headlineLarge),
              Text('Headline Medium', style: tt.headlineMedium),
              Text('Title Large', style: tt.titleLarge),
              Text('Body Large', style: tt.bodyLarge),
              Text('Body Medium', style: tt.bodyMedium),
              Text('Body Small (secondary)', style: tt.bodySmall),
              Text('Label Large', style: tt.labelLarge),
            ]),
            _Section('Buttons', [
              ElevatedButton(onPressed: () {}, child: const Text('Elevated Button')),
              const SizedBox(height: 8),
              OutlinedButton(onPressed: () {}, child: const Text('Outlined Button')),
              const SizedBox(height: 8),
              TextButton(onPressed: () {}, child: const Text('Text Button')),
            ]),
            _Section('Card', [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Card Title', style: tt.titleMedium),
                      const SizedBox(height: 4),
                      Text('Card body text — content lives here.', style: tt.bodyMedium),
                      const SizedBox(height: 4),
                      Text('Secondary caption', style: tt.bodySmall),
                    ],
                  ),
                ),
              ),
            ]),
            _Section('Input', [
              TextField(
                decoration: const InputDecoration(
                  labelText: 'Label',
                  hintText: 'Hint text',
                ),
              ),
            ]),
            _Section('Divider', [
              Text('Above the divider', style: tt.bodyMedium),
              const SizedBox(height: 8),
              const Divider(),
              const SizedBox(height: 8),
              Text('Below the divider', style: tt.bodyMedium),
            ]),
            _Section('Palette', [
              _Swatch('inkBlack', AppTheme.inkBlack, light: true),
              _Swatch('paperWhite', AppTheme.paperWhite),
              _Swatch('inkLight', AppTheme.inkLight, light: true),
              _Swatch('borderGray', AppTheme.borderGray),
            ]),
          ],
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title, this.children);
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 32),
        Text(title, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 4),
        const Divider(),
        const SizedBox(height: 12),
        ...children,
      ],
    );
  }
}

class _Swatch extends StatelessWidget {
  const _Swatch(this.name, this.color, {this.light = false});
  final String name;
  final Color color;
  final bool light;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: color,
              border: Border.all(color: AppTheme.borderGray),
              borderRadius: const BorderRadius.all(Radius.circular(4)),
            ),
          ),
          const SizedBox(width: 12),
          Text(name, style: Theme.of(context).textTheme.bodyMedium),
        ],
      ),
    );
  }
}
