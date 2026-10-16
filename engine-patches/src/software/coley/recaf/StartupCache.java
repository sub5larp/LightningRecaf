package software.coley.recaf;

/** Finish optional JDK startup recording off the UI thread, without retaining a resident helper. */
final class StartupCache {
    static void finishRecording() {
        if (!Boolean.getBoolean("lightning.aot.record")) return;
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                Class<?> type = Class.forName("jdk.management.HotSpotAOTCacheMXBean");
                Object bean = java.lang.management.ManagementFactory.class
                        .getMethod("getPlatformMXBean",Class.class).invoke(null,type);
                type.getMethod("endRecording").invoke(bean);
            } catch (Exception unavailable) {
                // Older JDKs finish recording on normal JVM exit instead.
            }
        },"lightning-startup-cache");
        thread.setDaemon(true);
        thread.start();
    }
}
