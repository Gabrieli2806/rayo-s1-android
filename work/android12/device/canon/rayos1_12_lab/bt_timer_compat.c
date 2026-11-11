/* Preserve valid POSIX timers; the factory library calls an uninitialized timer. */
#include <errno.h>
#include <time.h>

int rayo_timerset(timer_t timer, int flags, const struct itimerspec* value,
                  struct itimerspec* previous) {
    if (timer == (timer_t)0) {
        errno = EINVAL;
        return -1;
    }
    return timer_settime(timer, flags, value, previous);
}
