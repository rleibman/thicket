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

package thicket.signals

private[signals] object ThreadGuardPlatform {

  def owningThread: ThreadGuard =
    new ThreadGuard {
      private var owner: Thread | Null = null
      def check(op: String): Unit = {
        val current = Thread.currentThread()
        val o = owner
        if o == null then owner = current
        else if o.nn ne current then
          throw new IllegalStateException(
            s"$op was called on thread '${current.getName}' but the signal graph is owned by " +
              s"'${o.nn.getName}'. Signals are single-threaded; marshal onto the UI thread " +
              "(see docs/07 §7.7)."
          )
      }
    }

}
