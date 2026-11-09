/* Compatibility for the factory Bluetooth library's early, uninitialized timer.
 * Keep valid POSIX timers unchanged; return EINVAL for the invalid null handle.
 * The factory library calls this before its low-power timer has been created.
 */
#include <errno.h>
#include <time.h>

int rayo_timerset(timer_t timer, int flags, const struct itimerspec *value,
                  struct itimerspec *previous) {
    if (timer == (timer_t)0) { errno = EINVAL; return -1; }
    return timer_settime(timer, flags, value, previous);
}
