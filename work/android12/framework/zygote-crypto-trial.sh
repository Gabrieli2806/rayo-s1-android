#!/system/bin/sh
# RAM-bind diagnostic wrapper only. The image uses a normal init environment.
export LD_PRELOAD=/system/lib/librayo12_crypto_legacy.so
exec /data/local/tmp/rayo12-app-process-real "$@"
