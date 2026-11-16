import 'package:flutter/material.dart';

import '../native/hoard_native.dart';

/// Optional per-platform login. Logging in lets Hoard fetch posts that need an account
/// (Instagram often answers "login required" to anonymous requests).
class AccountsScreen extends StatefulWidget {
  const AccountsScreen({super.key});

  @override
  State<AccountsScreen> createState() => _AccountsScreenState();
}

class _AccountsScreenState extends State<AccountsScreen> {
  List<Account>? _accounts;
  String? _busyId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final a = await HoardNative.loginStatus();
      if (mounted) setState(() => _accounts = a);
    } catch (_) {
      if (mounted) setState(() => _accounts ??= const []);
    }
  }

  Future<void> _logIn(Account a) async {
    setState(() => _busyId = a.id);
    final ok = await HoardNative.startLogin(a.id);
    await _load();
    if (!mounted) return;
    setState(() => _busyId = null);
    if (ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Logged in to ${a.label}')),
      );
    }
  }

  Future<void> _logOut(Account a) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Log out of ${a.label}?'),
        content: const Text(
          'Hoard deletes the saved session from this phone. '
          'Public posts will still download without it.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Log out'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await HoardNative.logout(a.id);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final accounts = _accounts;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Accounts')),
      body: accounts == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text(
                  'Logging in is optional. It only matters for posts that refuse '
                  'anonymous downloads.',
                  style: theme.textTheme.bodyMedium,
                ),
                const SizedBox(height: 16),
                for (final a in accounts) _AccountCard(
                  account: a,
                  busy: _busyId == a.id,
                  onLogIn: () => _logIn(a),
                  onLogOut: () => _logOut(a),
                ),
                const SizedBox(height: 8),
                Card(
                  color: theme.colorScheme.surfaceContainerHigh,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.shield_outlined,
                            color: theme.colorScheme.primary),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Text(
                            'Heads up: automated downloads from a logged-in account can '
                            'get it flagged. Consider using a secondary account. Your '
                            'session is stored encrypted on this phone only.',
                            style: theme.textTheme.bodySmall,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}

class _AccountCard extends StatelessWidget {
  const _AccountCard({
    required this.account,
    required this.busy,
    required this.onLogIn,
    required this.onLogOut,
  });

  final Account account;
  final bool busy;
  final VoidCallback onLogIn;
  final VoidCallback onLogOut;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final on = account.loggedIn;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: on ? scheme.primaryContainer : scheme.surfaceContainerHighest,
              child: Icon(
                on ? Icons.check_rounded : Icons.person_outline,
                color: on ? scheme.onPrimaryContainer : scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(account.label, style: theme.textTheme.titleMedium),
                  Text(
                    on ? 'Logged in' : 'Not logged in',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: on ? scheme.primary : scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            if (busy)
              const SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              )
            else if (on)
              OutlinedButton(onPressed: onLogOut, child: const Text('Log out'))
            else
              FilledButton(onPressed: onLogIn, child: const Text('Log in')),
          ],
        ),
      ),
    );
  }
}
