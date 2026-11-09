#!/system/bin/sh
p=$(pidof surfaceflinger)
for t in /proc/$p/task/*; do
    printf '%s ' "${t##*/}"
    cat "$t/comm" | tr '\n' ' '
    cat "$t/wchan"
    echo
done
