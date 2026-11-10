#include <android/log.h>

/* Temporary diagnostic services only: keep source liblog messages visible
 * through init's stdio_to_kmsg even if the minimal logd setup loses messages. */
__attribute__((constructor)) static void route_diagnostic_logs(void) {
    __android_log_set_logger(__android_log_stderr_logger);
}
