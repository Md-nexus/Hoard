import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../native/hoard_native.dart';
import 'player_screen.dart';

/// Everything Hoard has saved, newest first. Refreshes itself while the app is open so a
/// download that finishes in the background shows up without pulling.
class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key});

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen>
    with WidgetsBindingObserver {
  List<MediaItem>? _items;
  bool _notificationsOn = true;
  Timer? _timer;
  final Map<String, Future<Uint8List?>> _thumbs = {};
  final TextEditingController _link = TextEditingController();
  String? _linkError;
  static final RegExp _urlRegex = RegExp(r'https?://[^\s]+');
  Timer? _clipTimer;
  String? _clipUrl; // a link found on the clipboard, offered as a one-tap download
  final Set<String> _handledClips = {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
    _startTimer();
    _checkClipboard();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _clipTimer?.cancel();
    _link.dispose();
    super.dispose();
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim();
    if (text == null || text.isEmpty) return;
    setState(() {
      _link.text = text;
      _linkError = null;
    });
  }

  Future<void> _download() async {
    final url = _urlRegex.firstMatch(_link.text)?.group(0);
    if (url == null) {
      setState(() => _linkError = 'Paste a link that starts with http:// or https://');
      return;
    }
    setState(() {
      _linkError = null;
      _link.clear();
    });
    FocusManager.instance.primaryFocus?.unfocus();
    try {
      await HoardNative.enqueueUrl(url);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Download started. Watch the notification.')),
      );
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not start the download.')),
      );
    }
  }

  /// Offers to download a link that was just copied. Only checked when the app opens or
  /// returns to the foreground (Android only lets the foreground app read the clipboard).
  void _checkClipboard() {
    _clipTimer?.cancel();
    _clipTimer = Timer(const Duration(milliseconds: 400), _readClipboard);
  }

  Future<void> _readClipboard() async {
    try {
      final data = await Clipboard.getData(Clipboard.kTextPlain);
      final url = _urlRegex.firstMatch(data?.text ?? '')?.group(0);
      if (!mounted || url == null || _handledClips.contains(url)) return;
      setState(() => _clipUrl = url);
    } catch (_) {}
  }

  Future<void> _downloadClip() async {
    final url = _clipUrl;
    if (url == null) return;
    _handledClips.add(url);
    setState(() => _clipUrl = null);
    try {
      await HoardNative.enqueueUrl(url);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Download started. Watch the notification.')),
      );
    } catch (_) {}
  }

  void _dismissClip() {
    final url = _clipUrl;
    if (url != null) _handledClips.add(url);
    setState(() => _clipUrl = null);
  }

  Future<void> _openViewer(List<MediaItem> items, int index) async {
    final deleted = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => PlayerScreen(items: items, initialIndex: index),
      ),
    );
    if (deleted == true) _load();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _load();
      _startTimer();
      _checkClipboard();
    } else {
      _timer?.cancel();
    }
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 4), (_) => _load());
  }

  Future<void> _load() async {
    try {
      final items = await HoardNative.listLibrary();
      final granted = await HoardNative.notificationsGranted();
      if (!mounted) return;
      setState(() {
        _items = items;
        _notificationsOn = granted;
      });
    } catch (_) {
      if (mounted) setState(() => _items ??= const []);
    }
  }

  Future<Uint8List?> _thumb(String uri) =>
      _thumbs.putIfAbsent(uri, () => HoardNative.thumbnail(uri));

  Future<void> _confirmDelete(MediaItem m) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete this file?'),
        content: Text(m.name),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final deleted = await HoardNative.deleteMedia(m.uri);
    if (!mounted) return;
    if (!deleted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Android wouldn't let Hoard delete that file.")),
      );
    }
    _thumbs.remove(m.uri);
    _load();
  }

  void _showActions(MediaItem m) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text(m.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(_describe(m)),
            ),
            ListTile(
              leading: const Icon(Icons.play_arrow_rounded),
              title: const Text('Play'),
              onTap: () {
                Navigator.pop(ctx);
                final list = _items ?? const <MediaItem>[];
                final i = list.indexWhere((e) => e.uri == m.uri);
                if (i >= 0) _openViewer(list, i);
              },
            ),
            ListTile(
              leading: const Icon(Icons.open_in_new),
              title: const Text('Open in another app'),
              onTap: () {
                Navigator.pop(ctx);
                HoardNative.openMedia(m);
              },
            ),
            ListTile(
              leading: const Icon(Icons.share_outlined),
              title: const Text('Share'),
              onTap: () {
                Navigator.pop(ctx);
                HoardNative.shareMedia(m);
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text('Delete'),
              onTap: () {
                Navigator.pop(ctx);
                _confirmDelete(m);
              },
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Hoard'),
        actions: [
          if (items != null && items.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: Text(
                  '${items.length} saved',
                  style: Theme.of(context).textTheme.labelLarge,
                ),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          _LinkBar(
            controller: _link,
            error: _linkError,
            onPaste: _paste,
            onDownload: _download,
          ),
          if (_clipUrl != null)
            _ClipboardBanner(
              url: _clipUrl!,
              onDownload: _downloadClip,
              onDismiss: _dismissClip,
            ),
          if (!_notificationsOn)
            _NotificationBanner(
              onAllow: () async {
                await HoardNative.requestNotifications();
                _load();
              },
            ),
          Expanded(
            child: items == null
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(
                    onRefresh: _load,
                    child: items.isEmpty ? const _EmptyState() : _grid(items),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _grid(List<MediaItem> items) {
    return GridView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(8),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        mainAxisSpacing: 6,
        crossAxisSpacing: 6,
        childAspectRatio: 0.6,
      ),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final m = items[i];
        return _Tile(
          item: m,
          thumb: _thumb(m.uri),
          onTap: () => _openViewer(items, i),
          onLongPress: () => _showActions(m),
        );
      },
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({
    required this.item,
    required this.thumb,
    required this.onTap,
    required this.onLongPress,
  });

  final MediaItem item;
  final Future<Uint8List?> thumb;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: Stack(
        fit: StackFit.expand,
        children: [
          ColoredBox(color: scheme.surfaceContainerHighest),
          FutureBuilder<Uint8List?>(
            future: thumb,
            builder: (context, snap) {
              final bytes = snap.data;
              if (bytes == null) {
                return Icon(
                  item.isVideo ? Icons.movie_outlined : Icons.image_outlined,
                  color: scheme.onSurfaceVariant,
                );
              }
              return Image.memory(bytes, fit: BoxFit.cover, gaplessPlayback: true);
            },
          ),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.center,
                end: Alignment.bottomCenter,
                colors: [Colors.transparent, Color(0xAA000000)],
              ),
            ),
          ),
          Positioned(
            left: 8,
            bottom: 6,
            child: Row(
              children: [
                Icon(
                  item.isVideo ? Icons.play_arrow_rounded : Icons.image_outlined,
                  size: 16,
                  color: Colors.white,
                ),
                const SizedBox(width: 2),
                Text(
                  item.isVideo ? _duration(item.durationMs) : 'Photo',
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ],
            ),
          ),
          Positioned.fill(
            child: Material(
              color: Colors.transparent,
              child: InkWell(onTap: onTap, onLongPress: onLongPress),
            ),
          ),
        ],
      ),
    );
  }
}

/// Paste-a-link box at the top of the library: the in-app alternative to the share sheet.
class _LinkBar extends StatelessWidget {
  const _LinkBar({
    required this.controller,
    required this.error,
    required this.onPaste,
    required this.onDownload,
  });

  final TextEditingController controller;
  final String? error;
  final VoidCallback onPaste;
  final VoidCallback onDownload;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              controller: controller,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => onDownload(),
              decoration: InputDecoration(
                hintText: 'Paste a link',
                errorText: error,
                isDense: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                prefixIcon: const Icon(Icons.link),
                suffixIcon: IconButton(
                  tooltip: 'Paste',
                  icon: const Icon(Icons.content_paste),
                  onPressed: onPaste,
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: FilledButton.icon(
              onPressed: onDownload,
              icon: const Icon(Icons.download),
              label: const Text('Get'),
            ),
          ),
        ],
      ),
    );
  }
}

class _ClipboardBanner extends StatelessWidget {
  const _ClipboardBanner({
    required this.url,
    required this.onDownload,
    required this.onDismiss,
  });

  final String url;
  final VoidCallback onDownload;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final host = Uri.tryParse(url)?.host.replaceFirst('www.', '') ?? url;
    return Material(
      color: scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 4, 4),
        child: Row(
          children: [
            Icon(Icons.content_paste_go, color: scheme.onPrimaryContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Copied link from $host',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: scheme.onPrimaryContainer),
              ),
            ),
            TextButton(onPressed: onDownload, child: const Text('Download')),
            IconButton(
              tooltip: 'Dismiss',
              icon: Icon(Icons.close, color: scheme.onPrimaryContainer),
              onPressed: onDismiss,
            ),
          ],
        ),
      ),
    );
  }
}

class _NotificationBanner extends StatelessWidget {
  const _NotificationBanner({required this.onAllow});

  final VoidCallback onAllow;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
        child: Row(
          children: [
            Icon(Icons.notifications_off_outlined, color: scheme.onErrorContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Notifications are off, so you will not see download progress.',
                style: TextStyle(color: scheme.onErrorContainer),
              ),
            ),
            TextButton(onPressed: onAllow, child: const Text('Allow')),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(32, 96, 32, 32),
      children: [
        Icon(Icons.download_for_offline_outlined,
            size: 72, color: theme.colorScheme.primary),
        const SizedBox(height: 20),
        Text(
          'Nothing saved yet',
          textAlign: TextAlign.center,
          style: theme.textTheme.titleLarge,
        ),
        const SizedBox(height: 12),
        Text(
          'Paste a link above, or in any app tap Share and pick Hoard. '
          'It downloads in the background and shows up here and in your gallery '
          'under Movies/Hoard.',
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium,
        ),
      ],
    );
  }
}

String _duration(int ms) {
  final total = (ms / 1000).round();
  final m = total ~/ 60;
  final s = total % 60;
  return '$m:${s.toString().padLeft(2, '0')}';
}

String _describe(MediaItem m) {
  final mb = m.size / (1024 * 1024);
  final size = mb >= 1 ? '${mb.toStringAsFixed(1)} MB' : '${(m.size / 1024).round()} KB';
  return m.isVideo ? '${_duration(m.durationMs)} · $size' : size;
}
