package ctf.server;

/**
 * Every player costs two threads (reader and writer). With many rooms that is thousands of threads, and a normal thread
 * reserves about 1 MB of stack: too much for a 1 GB server. On Java 21+ these are virtual threads, which cost a few KB.
 * The project still compiles for Java 17 (--release 17), so the Java 21 API is reached by reflection; on Java 17 the
 * fallback is a daemon platform thread with a small stack.
 */
final class Threads {
    private static final long SMALL_STACK = 256 * 1024;

    private Threads() {}

    static Thread start(String name, Runnable task) {
        try {
            Class<?> builder = Class.forName("java.lang.Thread$Builder");   // public interface: callable through reflection
            Object b = Thread.class.getMethod("ofVirtual").invoke(null);
            b = builder.getMethod("name", String.class).invoke(b, name);
            return (Thread) builder.getMethod("start", Runnable.class).invoke(b, task);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Thread t = new Thread(null, task, name, SMALL_STACK);
            t.setDaemon(true);
            t.start();
            return t;
        }
    }

    /** True when start() makes virtual threads (Java 21+). */
    static boolean virtual() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
