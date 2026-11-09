/* Diagnostic-only process-local guard for an invalid timer in the stock BT vendor library.
 * Does not change valid timer behavior. This is not a final low-power-management fix.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <time.h>

int timer_settime(timer_t timer, int flags, const struct itimerspec *value,
                  struct itimerspec *previous) {
    if (timer == (timer_t)0) { errno = EINVAL; return -1; }
    int (*next)(timer_t, int, const struct itimerspec *, struct itimerspec *) =
        dlsym(RTLD_NEXT, "timer_settime");
    if (!next) { errno = ENOSYS; return -1; }
    return next(timer, flags, value, previous);
}

int timer_gettime(timer_t timer, struct itimerspec *value) {
    if (timer == (timer_t)0) { errno = EINVAL; return -1; }
    int (*next)(timer_t, struct itimerspec *) = dlsym(RTLD_NEXT, "timer_gettime");
    if (!next) { errno = ENOSYS; return -1; }
    return next(timer, value);
}
