// Command-line harness for the S1 exports, run inside the iOS simulator via
// `xcrun simctl spawn`. This answers the S1 question (does the Scala Native runtime
// start and survive on iOS?) with precise timings and without any Xcode project in the
// way. The SwiftUI app in ios-app/ covers the "visible in a real app" half.
#include <mach/mach.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

extern void ScalaNativeInit(void);
extern char *scalaui_hello(void);
extern long long scalaui_alloc_stress(int seconds);
extern int scalaui_thread_test(int n);
extern char *scalaui_time(void);
extern void scalaui_register_callback(void (*cb)(char *));
extern void scalaui_fire(void);

static int callback_hits = 0;
static char callback_payload[256];

static void on_fire(char *msg) {
  callback_hits++;
  strncpy(callback_payload, msg, sizeof(callback_payload) - 1);
  callback_payload[sizeof(callback_payload) - 1] = '\0';
}

static double now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

// Resident size right now, which is what matters for "does the heap keep growing".
// ru_maxrss is a high-water mark and so cannot show the GC giving memory back.
static double rss_mb(void) {
  mach_task_basic_info_data_t info;
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count) !=
      KERN_SUCCESS)
    return -1.0;
  return info.resident_size / (1024.0 * 1024.0);
}

static double peak_rss_mb(void) {
  struct rusage ru;
  if (getrusage(RUSAGE_SELF, &ru) != 0) return -1.0;
  return ru.ru_maxrss / (1024.0 * 1024.0);
}

int main(int argc, char **argv) {
  int stress_seconds = (argc > 1) ? atoi(argv[1]) : 5;

  double t0 = now_ms();
  ScalaNativeInit();
  double t_init = now_ms() - t0;
  printf("RESULT init_ms=%.3f\n", t_init);

  // Measured separately from ScalaNativeInit so we can see whether the runtime is
  // actually up after init, or whether first-call still pays a lazy cost.
  double t1 = now_ms();
  char *greeting = scalaui_hello();
  double t_hello = now_ms() - t1;
  printf("RESULT hello_first_call_ms=%.3f\n", t_hello);
  printf("RESULT hello=\"%s\"\n", greeting);

  double t2 = now_ms();
  scalaui_hello();
  printf("RESULT hello_second_call_ms=%.3f\n", now_ms() - t2);

  char *timestr = scalaui_time();
  printf("RESULT time=\"%s\"\n", timestr);

  double t3 = now_ms();
  int checksum = scalaui_thread_test(8);
  printf("RESULT thread_test_8=%d thread_ms=%.3f\n", checksum, now_ms() - t3);

  scalaui_register_callback(on_fire);
  scalaui_fire();
  scalaui_fire();
  printf("RESULT callback_hits=%d payload=\"%s\"\n", callback_hits, callback_payload);

  // Run the stress in 30s chunks so RSS can be sampled between them. A flat curve is
  // the actual evidence that the GC collects; a single before/after pair cannot
  // distinguish collecting from merely not having run out of memory yet.
  const int chunk = 30;
  int chunks = (stress_seconds + chunk - 1) / chunk;
  if (chunks < 1) chunks = 1;
  printf("RESULT stress_seconds=%d chunks=%d rss_before_mb=%.2f\n", stress_seconds, chunks,
         rss_mb());
  fflush(stdout);

  long long allocated = 0;
  double t4 = now_ms();
  for (int k = 0; k < chunks; k++) {
    int this_chunk = (stress_seconds - k * chunk);
    if (this_chunk > chunk) this_chunk = chunk;
    if (this_chunk <= 0) break;
    allocated += scalaui_alloc_stress(this_chunk);
    printf("RESULT stress_sample chunk=%d elapsed_s=%d rss_mb=%.2f objects=%lld\n", k + 1,
           (k + 1) * chunk, rss_mb(), allocated);
    fflush(stdout);
  }
  double t_stress = now_ms() - t4;
  printf("RESULT alloc_stress_objects=%lld elapsed_ms=%.1f rss_after_mb=%.2f peak_rss_mb=%.2f\n",
         allocated, t_stress, rss_mb(), peak_rss_mb());

  // Proves the runtime is still healthy after the GC churn rather than merely not
  // having crashed yet.
  printf("RESULT post_stress_hello=\"%s\"\n", scalaui_hello());
  printf("RESULT post_stress_thread_test_8=%d\n", scalaui_thread_test(8));
  printf("RESULT ok\n");
  return 0;
}
