import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../native/hoard_native.dart';

/// Full-screen in-app viewer. Swipe up/down to move between saved items; videos autoplay and
/// loop, tap to pause. Pops with `true` if something was deleted so the library can refresh.
class PlayerScreen extends StatefulWidget {
  const PlayerScreen({
    super.key,
    required this.items,
    required this.initialIndex,
  });

  final List<MediaItem> items;
  final int initialIndex;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  late final PageController _pages;
  late List<MediaItem> _items;
  late int _index;
  bool _deletedAny = false;

  @override
  void initState() {
    super.initState();
    _items = List.of(widget.items);
    _index = widget.initialIndex.clamp(0, _items.length - 1);
    _pages = PageController(initialPage: _index);
  }

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  MediaItem get _current => _items[_index];

  Future<void> _delete() async {
    final m = _current;
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
      return;
    }
    _deletedAny = true;
    if (_items.length == 1) {
      Navigator.pop(context, true);
      return;
    }
    setState(() {
      _items.removeAt(_index);
      if (_index >= _items.length) _index = _items.length - 1;
    });
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.pop(context, _deletedAny);
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        extendBodyBehindAppBar: true,
        appBar: AppBar(
          backgroundColor: Colors.transparent,
          foregroundColor: Colors.white,
          elevation: 0,
          title: Text(
            _current.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 14),
          ),
          actions: [
            IconButton(
              tooltip: 'Share',
              icon: const Icon(Icons.share_outlined),
              onPressed: () => HoardNative.shareMedia(_current),
            ),
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'external') HoardNative.openMedia(_current);
                if (v == 'delete') _delete();
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'external', child: Text('Open in another app')),
                PopupMenuItem(value: 'delete', child: Text('Delete')),
              ],
            ),
          ],
        ),
        body: PageView.builder(
          controller: _pages,
          scrollDirection: Axis.vertical,
          itemCount: _items.length,
          onPageChanged: (i) => setState(() => _index = i),
          itemBuilder: (context, i) {
            final m = _items[i];
            return m.isVideo
                ? _VideoPage(key: ValueKey(m.uri), item: m, active: i == _index)
                : _ImagePage(key: ValueKey(m.uri), item: m);
          },
        ),
      ),
    );
  }
}

class _VideoPage extends StatefulWidget {
  const _VideoPage({super.key, required this.item, required this.active});

  final MediaItem item;
  final bool active;

  @override
  State<_VideoPage> createState() => _VideoPageState();
}

class _VideoPageState extends State<_VideoPage> {
  VideoPlayerController? _c;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    if (widget.active) _start();
  }

  @override
  void didUpdateWidget(_VideoPage old) {
    super.didUpdateWidget(old);
    if (widget.active && !old.active) _start();
    if (!widget.active && old.active) _stop();
  }

  @override
  void dispose() {
    _c?.dispose();
    super.dispose();
  }

  /// Controllers only exist for the visible page, so swiping through many videos does not
  /// keep a pile of decoders alive.
  Future<void> _start() async {
    final c = VideoPlayerController.contentUri(Uri.parse(widget.item.uri));
    _c = c;
    _failed = false;
    try {
      await c.initialize();
      if (!mounted || _c != c) return; // stopped meanwhile; _stop already disposed it
      await c.setLooping(true);
      await c.play();
      setState(() {});
    } catch (_) {
      if (mounted && _c == c) setState(() => _failed = true);
    }
  }

  void _stop() {
    final c = _c;
    _c = null;
    c?.dispose();
  }

  void _toggle() {
    final c = _c;
    if (c == null || !c.value.isInitialized) return;
    c.value.isPlaying ? c.pause() : c.play();
  }

  @override
  Widget build(BuildContext context) {
    if (_failed) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Colors.white70, size: 48),
            const SizedBox(height: 12),
            const Text(
              "Can't play this file here.",
              style: TextStyle(color: Colors.white70),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: () => HoardNative.openMedia(widget.item),
              child: const Text('Open in another app'),
            ),
          ],
        ),
      );
    }
    final c = _c;
    if (c == null || !c.value.isInitialized) {
      return const Center(child: CircularProgressIndicator());
    }
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _toggle,
      child: Stack(
        fit: StackFit.expand,
        children: [
          Center(
            child: AspectRatio(
              aspectRatio: c.value.aspectRatio,
              child: VideoPlayer(c),
            ),
          ),
          Center(
            child: ValueListenableBuilder<VideoPlayerValue>(
              valueListenable: c,
              builder: (context, v, _) => AnimatedOpacity(
                opacity: v.isPlaying ? 0 : 1,
                duration: const Duration(milliseconds: 150),
                child: const Icon(
                  Icons.play_circle_fill,
                  color: Colors.white70,
                  size: 72,
                ),
              ),
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                child: VideoProgressIndicator(
                  c,
                  allowScrubbing: true,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ImagePage extends StatelessWidget {
  const _ImagePage({super.key, required this.item});

  final MediaItem item;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: HoardNative.thumbnail(item.uri, size: 1600),
      builder: (context, snap) {
        final bytes = snap.data;
        if (bytes == null) {
          return const Center(child: CircularProgressIndicator());
        }
        return InteractiveViewer(
          child: Center(child: Image.memory(bytes, fit: BoxFit.contain)),
        );
      },
    );
  }
}
