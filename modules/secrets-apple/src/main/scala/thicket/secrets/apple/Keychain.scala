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

package thicket.secrets.apple

import scala.scalanative.unsafe.*

/** The C helper in `resources/scala-native/thicket_keychain.c`. Each returns the `OSStatus` Security gave. The Keychain
  * calls block — on a dialog, on `securityd` — so they are `@blocking`: the garbage collector does not wait for them.
  */
@extern
private[apple] object Keychain {

  @blocking
  def thk_keychain_get(
    dp:     CInt,
    svc:    Ptr[Byte],
    svcLen: CLongLong,
    key:    Ptr[Byte],
    keyLen: CLongLong,
    out:    Ptr[Ptr[Byte]],
    outLen: Ptr[CLongLong]
  ): CInt = extern

  def thk_keychain_free(p: Ptr[Byte]): Unit = extern

  @blocking
  def thk_keychain_set(
    dp:       CInt,
    svc:      Ptr[Byte],
    svcLen:   CLongLong,
    key:      Ptr[Byte],
    keyLen:   CLongLong,
    value:    Ptr[Byte],
    valueLen: CLongLong
  ): CInt = extern

  @blocking
  def thk_keychain_delete(
    dp:     CInt,
    svc:    Ptr[Byte],
    svcLen: CLongLong,
    key:    Ptr[Byte],
    keyLen: CLongLong
  ): CInt = extern

  def thk_keychain_disallow_interaction(): Unit = extern

  def thk_keychain_is_macos(): CInt = extern

  def thk_keychain_message(
    status: CInt,
    buf:    Ptr[Byte],
    len:    CLongLong
  ): Unit = extern

}

/** Security's statuses, from `<Security/SecBase.h>`. */
private[apple] object KeychainStatus {

  final val errSecSuccess = 0
  final val errSecItemNotFound = -25300
  final val errSecAuthFailed = -25293
  final val errSecInteractionNotAllowed = -25308
  final val errSecInvalidOwnerEdit = -25244
  final val errSecMissingEntitlement = -34018

}
