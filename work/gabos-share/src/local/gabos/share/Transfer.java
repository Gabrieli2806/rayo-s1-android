package local.gabos.share;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/** One offer from a sender: its items, the projector user's decision and the received files. */
final class Transfer {
    static final int AWAITING=0, ACCEPTED=1, DECLINED=2, TIMED_OUT=3, COMPLETE=4, CANCELLED=5;

    static final class Item {
        final String name, mime;
        final long size;
        File file;
        boolean done;
        Item(String name,String mime,long size){this.name=name;this.mime=mime;this.size=size;}
        boolean video(){return mime.startsWith("video/");}
    }

    final String id, sender;
    final boolean show;
    final Item[] items;
    final File dir;
    final CountDownLatch decision=new CountDownLatch(1);
    volatile int state=AWAITING;
    volatile long lastActivity=System.currentTimeMillis();
    volatile long bytesReceived;
    volatile boolean uploading;
    volatile boolean viewerOpen;
    final long created=System.currentTimeMillis();

    Transfer(String id,String sender,boolean show,Item[] items,File dir){
        this.id=id;this.sender=sender;this.show=show;this.items=items;this.dir=dir;
    }

    long totalBytes(){long t=0;for(Item i:items)t+=i.size;return t;}
    long largest(){long m=0;for(Item i:items)m=Math.max(m,i.size);return m;}
    int videos(){int n=0;for(Item i:items)if(i.video())n++;return n;}
    int photos(){return items.length-videos();}

    synchronized int receivedCount(){int n=0;for(Item i:items)if(i.done)n++;return n;}
    synchronized boolean allReceived(){for(Item i:items)if(!i.done)return false;return true;}

    /** Removes everything this transfer stored in the app cache. */
    void deleteFiles(){
        File[] files=dir.listFiles();
        if(files!=null)for(File f:files)//noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
