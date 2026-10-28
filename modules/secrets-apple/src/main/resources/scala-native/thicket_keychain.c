/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * thicket-secrets-apple's Keychain calls (#108), in C because the Security framework is C and its
 * queries are CFDictionaries: building one needs the addresses of kCFTypeDictionaryKeyCallBacks and
 * kCFTypeDictionaryValueCallBacks, which are struct globals, and that is one line here and awkward
 * from Scala Native. Scala Native compiles this file with its own clang (it is under
 * resources/scala-native), so there is no build step and no Swift.
 *
 * Strings cross as UTF-8 bytes with a length, never NUL-terminated, so a key or a secret may
 * hold any character. Every function returns the OSStatus Security gave.
 *
 * The frameworks are named in the object itself (LC_LINKER_OPTION, as Swift and clang modules
 * autolink), so an app that uses this links Security and CoreFoundation with no flags of its own:
 * not the macOS executable, not the iOS host's swiftc link, not an app outside this repository.
 */
#ifdef __APPLE__

#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <TargetConditionals.h>
#include <stdlib.h>
#include <string.h>

__asm__(".linker_option \"-framework\", \"Security\"");
__asm__(".linker_option \"-framework\", \"CoreFoundation\"");

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

/* The base query: one generic password, by service and account. dp: the data-protection keychain
 * (always so on iOS; on macOS only for an app with a keychain entitlement). */
static CFMutableDictionaryRef query(int dp, const char *svc, long long svcLen, const char *key, long long keyLen) {
  CFMutableDictionaryRef q =
      CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
  CFStringRef s = CFStringCreateWithBytes(NULL, (const UInt8 *)svc, svcLen, kCFStringEncodingUTF8, false);
  CFStringRef k = CFStringCreateWithBytes(NULL, (const UInt8 *)key, keyLen, kCFStringEncodingUTF8, false);
  CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword);
  if (s) CFDictionarySetValue(q, kSecAttrService, s);
  if (k) CFDictionarySetValue(q, kSecAttrAccount, k);
  if (dp) CFDictionarySetValue(q, kSecUseDataProtectionKeychain, kCFBooleanTrue);
  if (s) CFRelease(s);
  if (k) CFRelease(k);
  return q;
}

/* The secret under (svc, key): 0 and a malloc'd copy in *out (free with thk_keychain_free), or
 * errSecItemNotFound, or another status. */
int thk_keychain_get(int dp, const char *svc, long long svcLen, const char *key, long long keyLen, char **out, long long *outLen) {
  *out = NULL;
  *outLen = 0;
  CFMutableDictionaryRef q = query(dp, svc, svcLen, key, keyLen);
  CFDictionarySetValue(q, kSecReturnData, kCFBooleanTrue);
  CFDictionarySetValue(q, kSecMatchLimit, kSecMatchLimitOne);
  CFTypeRef result = NULL;
  OSStatus status = SecItemCopyMatching(q, &result);
  CFRelease(q);
  if (status == errSecSuccess) {
    if (result == NULL || CFGetTypeID(result) != CFDataGetTypeID()) {
      if (result) CFRelease(result);
      return errSecInternalError;
    }
    long long n = (long long)CFDataGetLength((CFDataRef)result);
    *out = malloc(n > 0 ? (size_t)n : 1);
    if (*out == NULL) {
      CFRelease(result);
      return errSecAllocate;
    }
    if (n > 0) memcpy(*out, CFDataGetBytePtr((CFDataRef)result), (size_t)n);
    *outLen = n;
    CFRelease(result);
  }
  return status;
}

void thk_keychain_free(char *p) { free(p); }

/* Keeps value under (svc, key): SecItemAdd, then SecItemUpdate when the item is already there. */
int thk_keychain_set(int dp, const char *svc, long long svcLen, const char *key, long long keyLen, const char *value,
                     long long valueLen) {
  CFDataRef data = CFDataCreate(NULL, (const UInt8 *)value, valueLen);
  CFMutableDictionaryRef add = query(dp, svc, svcLen, key, keyLen);
  CFDictionarySetValue(add, kSecValueData, data);
  /* Readable once the device has been unlocked after a restart, in the background too; never synced
   * (kSecAttrSynchronizable is left false) and never restored to another device. */
  CFDictionarySetValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly);
  OSStatus status = SecItemAdd(add, NULL);
  CFRelease(add);
  if (status == errSecDuplicateItem) {
    CFMutableDictionaryRef match = query(dp, svc, svcLen, key, keyLen);
    CFMutableDictionaryRef change =
        CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(change, kSecValueData, data);
    status = SecItemUpdate(match, change);
    CFRelease(match);
    CFRelease(change);
  }
  CFRelease(data);
  return status;
}

/* Forgets (svc, key). errSecItemNotFound is returned as it is; the caller decides it is not an error. */
int thk_keychain_delete(int dp, const char *svc, long long svcLen, const char *key, long long keyLen) {
  CFMutableDictionaryRef q = query(dp, svc, svcLen, key, keyLen);
  OSStatus status = SecItemDelete(q);
  CFRelease(q);
  return status;
}

/* macOS's login (file) keychain shows a dialog when an item's access list does not name the caller,
 * which blocks the calling thread until someone answers it, and forever where nobody can. Turned
 * off, the call fails at once instead. Process-wide, and the only switch the file keychain has:
 * kSecUseAuthenticationUIFail was measured not to stop that dialog. Nothing on iOS. */
void thk_keychain_disallow_interaction(void) {
#if TARGET_OS_OSX
  SecKeychainSetUserInteractionAllowed(false);
#endif
}

/* 1 on macOS, 0 on iOS and the simulator. */
int thk_keychain_is_macos(void) {
#if TARGET_OS_OSX
  return 1;
#else
  return 0;
#endif
}

/* What Security says a status means, as UTF-8 in buf (at most len bytes, NUL-terminated), or "". */
void thk_keychain_message(int status, char *buf, long long len) {
  if (len <= 0) return;
  buf[0] = 0;
  CFStringRef s = SecCopyErrorMessageString(status, NULL);
  if (s) {
    CFStringGetCString(s, buf, len, kCFStringEncodingUTF8);
    CFRelease(s);
  }
}

#endif
