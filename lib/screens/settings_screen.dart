import 'package:flutter/material.dart';

import '../native/hoard_native.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  bool? _notificationsOn;
  bool _updating = false;
  String? _updateStatus;
  List<LinkApp> _linkApps = const [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _loadLinkApps();
  }

  Future<void> _loadLinkApps() async {
    try {
      final apps = await HoardNative.linkApps();
      if (mounted) setState(() => _linkApps = apps);
    } catch (_) {}
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final ok = await HoardNative.notificationsGranted();
      if (mounted) setState(() => _notificationsOn = ok);
    } catch (_) {}
  }

  Future<void> _allowNotifications() async {
    final ok = await HoardNative.requestNotifications();
    if (!mounted) return;
    setState(() => _notificationsOn = ok);
    if (!ok) await HoardNative.openNotificationSettings();
  }

  Future<void> _update() async {
    setState(() {
      _updating = true;
      _updateStatus = null;
    });
    String msg;
    try {
      msg = await HoardNative.updateYtDlp();
    } catch (e) {
      msg = 'Update failed: $e';
    }
    if (mounted) {
      setState(() {
        _updating = false;
        _updateStatus = msg;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final on = _notificationsOn;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          const _Section('Notifications'),
          ListTile(
            leading: Icon(
              on == false
                  ? Icons.notifications_off_outlined
                  : Icons.notifications_active_outlined,
            ),
            title: Text(on == false ? 'Notifications are off' : 'Notifications are on'),
            subtitle: const Text(
              'Downloads pop up when they start and when they finish.',
            ),
            trailing: on == false
                ? FilledButton(
                    onPressed: _allowNotifications,
                    child: const Text('Allow'),
                  )
                : null,
          ),
          ListTile(
            leading: const Icon(Icons.tune),
            title: const Text('Notification settings'),
            subtitle: const Text('Sound, pop-up and lock screen behaviour'),
            onTap: HoardNative.openNotificationSettings,
          ),
          const _Section('Links'),
          ListTile(
            leading: const Icon(Icons.link),
            title: const Text('1. Let Hoard open links'),
            subtitle: const Text(
              'Turn on the sites you want (TikTok, Instagram, Reddit, YouTube...). '
              'Android makes you tick these yourself; apps cannot do it for you.',
            ),
            trailing: OutlinedButton(
              onPressed: HoardNative.openLinkSettings,
              child: const Text('Open'),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: Text(
              '2. Official apps win by default. Android always lets a site\'s own app '
              'take its links first, so a tap on a TikTok link goes to TikTok even with '
              'Hoard switched on. To change that, turn off "Open supported links" for '
              'the app. Its links then go to Hoard and start downloading right away.',
              style: theme.textTheme.bodySmall,
            ),
          ),
          for (final app in _linkApps)
            ListTile(
              leading: const Icon(Icons.apps),
              title: Text(app.label),
              subtitle: Text(app.package, style: theme.textTheme.bodySmall),
              trailing: OutlinedButton(
                onPressed: () => HoardNative.openLinkSettings(package: app.package),
                child: const Text('Open settings'),
              ),
            ),
          const _Section('Downloader'),
          ListTile(
            leading: const Icon(Icons.system_update_alt),
            title: const Text('Update yt-dlp'),
            subtitle: Text(
              _updateStatus ??
                  'Hoard updates it daily. Do this if a site stops working.',
            ),
            trailing: _updating
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2.5),
                  )
                : OutlinedButton(onPressed: _update, child: const Text('Update')),
          ),
          const _Section('About'),
          const ListTile(
            leading: Icon(Icons.folder_outlined),
            title: Text('Saved files'),
            subtitle: Text('Movies/Hoard and Pictures/Hoard, visible in your gallery'),
          ),
          ListTile(
            leading: const Icon(Icons.ios_share),
            title: const Text('How to save a video'),
            subtitle: Text(
              'Paste a link in the Library tab, tap Share in any app and choose Hoard, or '
              'tap a link and pick Hoard. Works with anything yt-dlp supports.',
              style: theme.textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(
        title,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            ),
      ),
    );
  }
}
