import java.util.Arrays;

/** Pure Java kernel/ART smoke test; no Android framework, display or app data. */
public final class RayoRuntimeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("vm=" + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.vm.version"));
        System.out.println("kernel=" + System.getProperty("os.version"));
        final int[] values = new int[4096];
        Thread worker = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < values.length; i++) values[i] = values.length - i;
                Arrays.sort(values);
            }
        });
        long start = System.nanoTime();
        worker.start();
        worker.join();
        if (values[0] != 1 || values[4095] != 4096) throw new AssertionError("thread/array result");
        System.out.println("thread_array_ns=" + (System.nanoTime() - start));
        System.out.println("ART12_MANAGED_RUNTIME_PASS");
    }
}
