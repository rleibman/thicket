# thicket-secrets

Small secrets that outlive the app, such as a token or a password, kept by the platform's
secure store. The plan is in `docs/16-device-services.md`.

| Module | Platform | Store |
|---|---|---|
| `thicket-secrets` | JVM, Native | the API: `Secrets` (`get`, `set`, `delete`), `SecretsException`; `testing.MemorySecrets`, `testing.SecretsConformance` |
| `thicket-secrets-gtk` | Scala Native | the freedesktop Secret Service (GNOME Keyring, KeePassXC, KWallet's bridge), through libsecret |
| `thicket-secrets-android` | Android | AES-256-GCM, under a key that stays in the Android Keystore; the ciphertext in a private `SharedPreferences` file |
| `thicket-secrets-apple` | Scala Native (macOS, iOS) | the Keychain: generic passwords, service = the bundle id, account = the key |

```scala
// On a worker thread: every call may block.
GtkSecrets.open("dev.example.app") match {          // AppleSecrets.open(bundleId); AndroidSecrets(activity)
  case Right(secrets) => secrets.set("token", token)
  case Left(reason)   => // no Secret Service on this desktop; sign in each time
}
```

- **Off the UI thread.** On GTK each call is a D-Bus round trip to the keyring, which may be
  waiting for the user to unlock it. If there is no default collection and no prompter, it
  waits for good.
- **When there's no store:** on GTK, `open` returns `Left(reason)` if libsecret isn't
  installed or no Secret Service is on the session bus. libsecret is loaded with `dlopen`
  (`libsecret-1.so.0`), so an app that uses this still starts without it, and building it
  needs no `-dev` package. When the store itself fails, for example a locked keyring or a
  daemon that has gone away, the call throws `SecretsException`.
- **On GTK**, each secret is an item in the default collection. Its attributes are `app` (the
  app id) and `key`, under the schema `dev.thicket.Secret`, and Seahorse shows it labelled
  `"<app id>: <key>"`. Apps don't see each other's secrets.
- **On Android**, each ciphertext is bound to its key through GCM's associated data, so a
  value copied under another key doesn't decrypt. Auto backup can restore the preferences
  file to a new device, but never the Keystore key, so a restored secret can't be read:
  `get` throws, and the app should sign in again.
- **On Apple**, each secret is a Keychain generic password (`kSecClassGenericPassword`):
  `kSecAttrService` is the service `open` was given (the bundle id) and `kSecAttrAccount` is
  the key. Items are `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: readable in the
  background once the device has been unlocked after a restart, never synced, and never
  restored to another device, as Android's Keystore key isn't. `set` is `SecItemAdd`, then
  `SecItemUpdate` if the item exists. Deleting a missing key (`errSecItemNotFound`) is not
  an error. The calls are in a small C file that Scala Native compiles. It names the Security
  and CoreFoundation frameworks in its object file, so an app needs no link flags. There is
  no Swift.
  - **iOS:** the app's own keychain. **The app must carry keychain entitlements**
    (`application-identifier` and `keychain-access-groups`, the bundle id), even on the
    simulator. Without them every call is `errSecMissingEntitlement` (-34018) and `open`
    returns `Left`. A signed device build has them from its provisioning profile. For the
    simulator, the example hosts link them into a `__TEXT,__entitlements` section, as Xcode
    does (`examples/ios-host/run-ios-app.sh`). Entitlements in an ad hoc signature got the
    app killed at launch instead.
  - **macOS, signed with keychain entitlements:** the data-protection keychain, per app, as
    on iOS. Not measured here: this Mac has no signing identity.
  - **macOS, unsigned or ad hoc** (every `nativeLink`, the gallery): the data-protection
    keychain answers -34018, so `open` uses the user's login keychain. Its items are bound
    to the build that made them. A rebuilt binary can't read them (`get` throws
    `SecretsException`, naming the `security delete-generic-password` command that removes
    them) or delete them, and the login keychain's "allow access" dialogs are turned off for
    the process, so nothing waits on one. `bin/verify-apple.sh` removes the gallery's item
    before each run for this reason. Measured with a second build of the same program: a
    read blocked on the dialog, and an access list allowing any application didn't help
    either. The likely cause is the item's partition list, which names the creating build;
    that part was inferred, not measured.
- **The same behaviour on every platform** is checked by `testing.SecretsConformance`'s 11
  checks. They run against `MemorySecrets` in the unit tests, the Secret Service in
  `secrets-gtk`'s tests and the GTK gallery, the Keystore store in the Android gallery, and
  the Keychain in `secrets-apple`'s tests (a Mac only: `bin/verify-apple.sh`'s `secrets`
  step, which uses its own service and leaves nothing behind) and the macOS and iOS
  galleries.
- **Tests and CI:** `bin/with-keyring.sh <command>` runs a command against a Secret Service of
  its own: a private session bus, and a gnome-keyring unlocked with a throwaway password.
  Your own keyring isn't touched. With `THICKET_REQUIRE_SECRET_SERVICE=1`, `secrets-gtk`'s
  tests fail rather than skip when there's no service; CI sets it.
