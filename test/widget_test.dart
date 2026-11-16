import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:hoard/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.hoard/commands'),
      (call) async {
        switch (call.method) {
          case 'listLibrary':
            return <Object?>[];
          case 'loginStatus':
            return <Object?>[
              {'id': 'instagram', 'label': 'Instagram', 'loggedIn': false},
              {'id': 'tiktok', 'label': 'TikTok', 'loggedIn': true},
            ];
          default:
            return true;
        }
      },
    );
  });

  testWidgets('Library shows the empty state with how-to', (tester) async {
    await tester.pumpWidget(const HoardApp());
    await tester.pump();

    expect(find.text('Hoard'), findsWidgets);
    expect(find.text('Nothing saved yet'), findsOneWidget);

    // Dispose the tree so the library's refresh timer is cancelled.
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Pasting a link starts a download, junk is rejected', (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.hoard/commands'),
      (call) async {
        calls.add(call);
        if (call.method == 'listLibrary') return <Object?>[];
        return true;
      },
    );

    await tester.pumpWidget(const HoardApp());
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'not a link');
    await tester.tap(find.text('Get'));
    await tester.pump();
    expect(find.textContaining('Paste a link that starts'), findsOneWidget);

    await tester.enterText(
      find.byType(TextField),
      'look https://www.instagram.com/reel/abc/ wow',
    );
    await tester.tap(find.text('Get'));
    await tester.pump();

    final enqueue = calls.firstWhere((c) => c.method == 'enqueueUrl');
    expect((enqueue.arguments as Map)['url'], 'https://www.instagram.com/reel/abc/');

    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Accounts tab lists platforms with login state', (tester) async {
    await tester.pumpWidget(const HoardApp());
    await tester.pump();

    await tester.tap(find.text('Accounts'));
    await tester.pumpAndSettle();

    expect(find.text('Instagram'), findsOneWidget);
    expect(find.text('Log in'), findsOneWidget);
    expect(find.text('Log out'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
  });
}
