import 'package:flutter/services.dart';

/// One saved file from Movies/Hoard or Pictures/Hoard.
class MediaItem {
  const MediaItem({
    required this.uri,
    required this.name,
    required this.size,
    required this.addedSeconds,
    required this.mime,
    required this.isVideo,
    required this.durationMs,
  });

  final String uri;
  final String name;
  final int size;
  final int addedSeconds;
  final String mime;
  final bool isVideo;
  final int durationMs;

  factory MediaItem.fromMap(Map<Object?, Object?> m) => MediaItem(
        uri: m['uri'] as String,
        name: (m['name'] as String?) ?? 'Untitled',
        size: (m['size'] as num?)?.toInt() ?? 0,
        addedSeconds: (m['added'] as num?)?.toInt() ?? 0,
        mime: (m['mime'] as String?) ?? 'video/mp4',
        isVideo: (m['isVideo'] as bool?) ?? true,
        durationMs: (m['durationMs'] as num?)?.toInt() ?? 0,
      );
}

/// A platform Hoard can optionally log in to.
class Account {
  const Account({required this.id, required this.label, required this.loggedIn});

  final String id;
  final String label;
  final bool loggedIn;

  factory Account.fromMap(Map<Object?, Object?> m) => Account(
        id: m['id'] as String,
        label: m['label'] as String,
        loggedIn: (m['loggedIn'] as bool?) ?? false,
      );
}

/// An installed official app that may claim links before Hoard does.
class LinkApp {
  const LinkApp({required this.package, required this.label});

  final String package;
  final String label;

  factory LinkApp.fromMap(Map<Object?, Object?> m) =>
      LinkApp(package: m['package'] as String, label: m['label'] as String);
}

/// Thin wrapper over the `com.hoard/commands` MethodChannel implemented in MainActivity.kt.
class HoardNative {
  HoardNative._();

  static const _ch = MethodChannel('com.hoard/commands');

  static Future<bool> notificationsGranted() async =>
      (await _ch.invokeMethod<bool>('notificationsGranted')) ?? false;

  static Future<bool> requestNotifications() async =>
      (await _ch.invokeMethod<bool>('requestNotifications')) ?? false;

  static Future<void> openNotificationSettings() =>
      _ch.invokeMethod<void>('openNotificationSettings');

  /// Opens Android's "Open by default" page for Hoard, or for another app ([package]).
  static Future<void> openLinkSettings({String? package}) =>
      _ch.invokeMethod<void>('openLinkSettings', {'package': package});

  /// Installed official apps (TikTok, Instagram...) that take these links before Hoard.
  static Future<List<LinkApp>> linkApps() async {
    final raw = await _ch.invokeListMethod<Object?>('linkApps') ?? const [];
    return raw.whereType<Map<Object?, Object?>>().map(LinkApp.fromMap).toList();
  }

  /// Starts a download for [url] (same pipeline as the share sheet).
  static Future<bool> enqueueUrl(String url) async =>
      (await _ch.invokeMethod<bool>('enqueueUrl', {'url': url})) ?? false;

  static Future<String> updateYtDlp() async =>
      (await _ch.invokeMethod<String>('updateYtDlp')) ?? 'Done';

  static Future<List<MediaItem>> listLibrary() async {
    final raw = await _ch.invokeListMethod<Object?>('listLibrary') ?? const [];
    return raw
        .whereType<Map<Object?, Object?>>()
        .map(MediaItem.fromMap)
        .toList();
  }

  static Future<Uint8List?> thumbnail(String uri, {int size = 360}) =>
      _ch.invokeMethod<Uint8List>('thumbnail', {'uri': uri, 'size': size});

  static Future<bool> deleteMedia(String uri) async =>
      (await _ch.invokeMethod<bool>('deleteMedia', {'uri': uri})) ?? false;

  static Future<void> openMedia(MediaItem m) =>
      _ch.invokeMethod<void>('openMedia', {'uri': m.uri, 'mime': m.mime});

  static Future<void> shareMedia(MediaItem m) =>
      _ch.invokeMethod<void>('shareMedia', {'uri': m.uri, 'mime': m.mime});

  static Future<List<Account>> loginStatus() async {
    final raw = await _ch.invokeListMethod<Object?>('loginStatus') ?? const [];
    return raw.whereType<Map<Object?, Object?>>().map(Account.fromMap).toList();
  }

  /// Opens the in-app login. Completes with true once a session was saved.
  static Future<bool> startLogin(String platformId) async =>
      (await _ch.invokeMethod<bool>('startLogin', {'platform': platformId})) ??
      false;

  static Future<void> logout(String platformId) =>
      _ch.invokeMethod<void>('logout', {'platform': platformId});
}
