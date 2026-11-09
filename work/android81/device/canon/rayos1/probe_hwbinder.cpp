// Supervised, temporary test only. Does not access storage partitions.
#include <hwbinder/Binder.h>
#include <hwbinder/IPCThreadState.h>
#include <hwbinder/Parcel.h>
#include <hwbinder/ProcessState.h>
#include <linux/android/binder.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

using android::sp;
using android::status_t;
using namespace android::hardware;

static const char message[] = "Rayo scatter-gather request";
static const char response[] = "Rayo scatter-gather reply";
static const uint32_t transaction = 0x5241594f;
static volatile sig_atomic_t child_pid = -1;
struct Payload { uint32_t magic; const char *text; };

static void timed_out(int) {
    if (child_pid > 0) kill(child_pid, SIGKILL);
    static const char warning[] = "HWBINDER_TIMEOUT\n";
    write(STDERR_FILENO, warning, sizeof(warning)-1);
    _exit(124);
}

class Echo final : public BHwBinder {
    status_t onTransact(uint32_t code, const Parcel& data, Parcel *reply,
                        uint32_t flags, TransactCallback callback) override {
        if (code != transaction) return BHwBinder::onTransact(code,data,reply,flags,callback);
        const void *parent = nullptr, *text = nullptr;
        size_t parent_handle = 0, text_handle = 0;
        if (data.readBuffer(sizeof(Payload), &parent_handle, &parent) != android::OK)
            return android::BAD_VALUE;
        const Payload *payload = static_cast<const Payload *>(parent);
        if (data.readEmbeddedBuffer(sizeof(message), &text_handle, parent_handle,
                offsetof(Payload,text), &text) != android::OK)
            return android::BAD_VALUE;
        if (payload->magic != transaction || payload->text != text ||
                memcmp(text,message,sizeof(message))) return android::BAD_VALUE;
        size_t handle = 0;
        status_t status = reply->writeBuffer(response,sizeof(response),&handle);
        if (status == android::OK && callback) callback(*reply);
        return status;
    }
};

int main() {
    setbuf(stdout,nullptr);
    int binder = open("/dev/hwbinder",O_RDWR|O_CLOEXEC);
    struct binder_version version = {};
    if (binder < 0 || ioctl(binder,BINDER_VERSION,&version) < 0) {
        perror("hwbinder version"); return 2;
    }
    printf("HWBINDER_PROTOCOL=%d\n",version.protocol_version);
    close(binder);
    int ready[2];
    if (pipe(ready)) return 2;
    signal(SIGALRM,timed_out);
    pid_t server = fork();
    if (server < 0) return 2;
    if (!server) {
        prctl(PR_SET_PDEATHSIG,SIGKILL,0,0,0);
        alarm(12);
        close(ready[0]);
        sp<ProcessState> state = ProcessState::self();
        sp<Echo> echo = new Echo();
        state->setContextObject(echo);
        IPCThreadState::self()->setTheContextObject(echo);
        if (!state->becomeContextManager(nullptr,nullptr)) _exit(3);
        char ok = 1;
        if (write(ready[1],&ok,1) != 1) _exit(3);
        close(ready[1]);
        IPCThreadState::self()->joinThreadPool();
        _exit(0);
    }
    child_pid=server;
    alarm(8);
    close(ready[1]);
    char ok=0;
    int result=4;
    if (read(ready[0],&ok,1)==1 && ok==1) {
        close(ready[0]);
        sp<IBinder> service=ProcessState::self()->getContextObject(nullptr);
        if (service != nullptr) {
            Parcel request,reply;
            Payload payload={transaction,message};
            size_t parent=0,child=0;
            status_t status=request.writeBuffer(&payload,sizeof(payload),&parent);
            if (status==android::OK)
                status=request.writeEmbeddedBuffer(message,sizeof(message),&child,parent,offsetof(Payload,text));
            if (status==android::OK) status=service->transact(transaction,request,&reply);
            printf("HWBINDER_TRANSACTION_STATUS=%d\n",status);
            const void *answer=nullptr;
            size_t handle=0;
            if (status==android::OK && reply.readBuffer(sizeof(response),&handle,&answer)==android::OK &&
                    !memcmp(answer,response,sizeof(response))) {
                printf("HWBINDER_SCATTER_GATHER_PASS\n"); result=0;
            }
            int vendor=open("/dev/vndbinder",O_RDWR|O_CLOEXEC);
            if(vendor<0 || ioctl(vendor,BINDER_SET_CONTEXT_MGR,0)<0) {
                perror("vndbinder independent context"); result=5;
            } else printf("BINDER_DOMAINS_INDEPENDENT_PASS\n");
            if(vendor>=0) close(vendor);
        }
    }
    kill(server,SIGKILL);
    waitpid(server,nullptr,0);
    child_pid=-1;
    alarm(0);
    return result;
}
