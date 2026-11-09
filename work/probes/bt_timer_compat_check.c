#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

int main(void) {
    void *library = dlopen("/vendor/lib/libtg.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) { puts(dlerror()); return 1; }
    int (*guard)(timer_t, int, const struct itimerspec *, struct itimerspec *) =
        dlsym(library, "rayo_timerset");
    if (!guard) return 2;
    struct itimerspec interval;
    memset(&interval, 0, sizeof(interval));
    interval.it_value.tv_sec = 1;
    errno = 0;
    if (guard((timer_t)0, 0, &interval, NULL) != -1 || errno != EINVAL) return 3;
    puts("NULL_TIMER_RETURNS_EINVAL");
    struct sigevent event;
    memset(&event, 0, sizeof(event));
    event.sigev_notify = SIGEV_NONE;
    timer_t timer;
    if (timer_create(CLOCK_MONOTONIC, &event, &timer)) return 4;
    int result = guard(timer, 0, &interval, NULL);
    struct itimerspec remaining;
    int queried = timer_gettime(timer, &remaining);
    timer_delete(timer);
    if (result || queried || (remaining.it_value.tv_sec == 0 && remaining.it_value.tv_nsec == 0)) return 5;
    puts("VALID_TIMER_SET_AND_QUERY_PASS");
    return 0;
}
