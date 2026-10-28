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

/* thicket-http-gtk: libcurl's variadic curl_easy_setopt.
 *
 * libcurl is loaded with dlopen (Libcurl.scala), so these take the function pointer dlsym found. Scala Native
 * cannot call a variadic function through a pointer; the cast here makes the C compiler emit a proper variadic
 * call, which on x86-64 is not the same as an ordinary one. No curl header is needed, and nothing links -lcurl.
 * CURLcode and the option ids are C enums, so int; curl_off_t is 64 bits. */

typedef int (*thk_curl_setopt_fn)(void *, int, ...);

int thk_curl_setopt_long(void *fn, void *curl, int option, long value) {
  return ((thk_curl_setopt_fn)fn)(curl, option, value);
}

int thk_curl_setopt_off(void *fn, void *curl, int option, long long value) {
  return ((thk_curl_setopt_fn)fn)(curl, option, value);
}

int thk_curl_setopt_ptr(void *fn, void *curl, int option, void *value) {
  return ((thk_curl_setopt_fn)fn)(curl, option, value);
}
