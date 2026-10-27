# thicket-secrets

Small secrets that outlive the app, such as a token or a password, kept by the platform's
secure store. The plan is in `docs/16-device-services.md`.

| Module | Platform | Store |
|---|---|---|
| `thicket-secrets` | JVM, Native | the API: `Secrets` (`get`, `set`, `delete`), `SecretsException`; `testing.MemorySecrets`, `testing.SecretsConformance` |
| `thicket-secrets-gtk` | Scala Native | the freedesktop Secret Service (GNOME Keyring, KeePassXC, KWallet's bridge), through libsecret |
| `thicket-secrets-android` | Android | AES-256-GCM, under a key that stays in the Android Keystore; the ciphertext in a private `SharedPreferences` file |
| Apple | | not yet: the Keychain, a Mac issue |

```scala
// On a worker thread: every call may block.
GtkSecrets.open("dev.example.app") match {          // or AndroidSecrets(activity)
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
- **The same behaviour on every platform** is checked by `testing.SecretsConformance`'s 11
  checks. They run against `MemorySecrets` in the unit tests, the Secret Service in
  `secrets-gtk`'s tests and the GTK gallery, and the Keystore store in the Android gallery.
- **Tests and CI:** `bin/with-keyring.sh <command>` runs a command against a Secret Service of
  its own: a private session bus, and a gnome-keyring unlocked with a throwaway password.
  Your own keyring isn't touched. With `THICKET_REQUIRE_SECRET_SERVICE=1`, `secrets-gtk`'s
  tests fail rather than skip when there's no service; CI sets it.
