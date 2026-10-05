import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.service.SeckillEventCache;

import java.io.File;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Session-only deterministic reproduction (not part of the repo).
 * Uses the real compiled SeckillEventCache + Caffeine; the repository is a proxy whose findById blocks on a latch.
 * args: OUT_DIR WAITERS
 * Steps: (1) loader thread enters findById and blocks; (2) WAITERS virtual threads call get(same key), all started
 * (latch) and observed BLOCKED/WAITING (polled state, bounded); (3) an unrelated virtual-thread probe is submitted;
 * (4) jcmd Thread.print -l + Thread.dump_to_file while blocked; (5) finally: release, verify recovery, JFR summary.
 * Exit 0 always when the harness itself worked; the verdict is printed as RESULT lines.
 */
public class CacheStallRepro {
    static final long KEY = 1L;
    static final CountDownLatch loaderEntered = new CountDownLatch(1);
    static final CountDownLatch release = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int waiters = Integer.parseInt(args[1]);
        Files.createDirectories(out);
        long pid = ProcessHandle.current().pid();
        System.out.printf("pid=%d java=%s parallelism=%s maxPoolSize=%s trackAllThreads=%s waiters=%d cpus=%d%n", pid,
                System.getProperty("java.version"), System.getProperty("jdk.virtualThreadScheduler.parallelism", "default"),
                System.getProperty("jdk.virtualThreadScheduler.maxPoolSize", "default"),
                System.getProperty("jdk.trackAllThreads"), waiters, Runtime.getRuntime().availableProcessors());

        Recording rec = new Recording();
        rec.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        rec.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10)).withStackTrace();
        rec.start();

        SeckillEventRepository repo = (SeckillEventRepository) Proxy.newProxyInstance(
                CacheStallRepro.class.getClassLoader(), new Class<?>[]{SeckillEventRepository.class}, (p, m, a) -> {
                    if (m.getName().equals("findById")) {
                        loaderEntered.countDown();
                        if (!release.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
                        return Optional.of(SeckillEvent.builder().id((Long) a[0]).productId(10L)
                                .seckillPrice(new BigDecimal("9.90")).seckillStock(5)
                                .startTime(Instant.parse("2026-10-04T10:00:00Z"))
                                .endTime(Instant.parse("2026-10-04T10:10:00Z")).build());
                    }
                    if (m.getName().equals("toString")) return "BlockingRepoProxy";
                    throw new UnsupportedOperationException(m.getName());
                });
        SeckillEventCache cache = new SeckillEventCache(repo, Duration.ofSeconds(5));

        List<Thread> threads = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(waiters);
        CountDownLatch probeDone = new CountDownLatch(1);
        boolean probeInTime = false;
        long probeMs = -1;
        try {
            Thread loader = Thread.ofVirtual().name("repro-loader").start(() -> cache.get(KEY));
            threads.add(loader);
            boolean entered = loaderEntered.await(10, TimeUnit.SECONDS);
            System.out.println("STEP loaderEntered=" + entered + " loaderState=" + loader.getState());
            if (!entered) { System.out.println("RESULT harness: loader did not enter findById"); return; }

            for (int i = 0; i < waiters; i++) {
                threads.add(Thread.ofVirtual().name("repro-waiter-" + i).start(() -> { started.countDown(); cache.get(KEY); }));
            }
            boolean allStarted = started.await(10, TimeUnit.SECONDS);
            Map<Thread.State, Integer> states = waitForParked(threads.subList(1, threads.size()), 10_000);
            System.out.println("STEP waitersStarted=" + allStarted + " started.count=" + started.getCount()
                    + " waiterStates=" + states + " loaderState=" + loader.getState());

            long t0 = System.nanoTime();
            Thread.ofVirtual().name("repro-unrelated-probe").start(probeDone::countDown);
            probeInTime = probeDone.await(5, TimeUnit.SECONDS);
            probeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            System.out.println("STEP unrelatedProbeCompletedWithin5s=" + probeInTime + " (" + probeMs + " ms)");

            jcmd(pid, out.resolve("thread-print.txt"), "Thread.print", "-l");
            jcmd(pid, out.resolve("thread-dump.cmd.txt"), "Thread.dump_to_file", "-format=plain",
                    out.resolve("thread-dump.txt").toString());
        } finally {
            release.countDown();
            long t1 = System.nanoTime();
            boolean recovered = probeDone.await(30, TimeUnit.SECONDS);
            System.out.println("STEP afterRelease probeCompleted=" + recovered + " ("
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1) + " ms after release)");
            int alive = 0;
            for (Thread t : threads) { t.join(Duration.ofSeconds(30)); if (t.isAlive()) alive++; }
            System.out.println("STEP threadsStillAlive=" + alive);
            try { cache.getClass().getMethod("shutdown").invoke(cache); System.out.println("STEP cache.shutdown() called"); }
            catch (NoSuchMethodException e) { System.out.println("STEP cache has no shutdown()"); }
            rec.stop();
            Path jfr = out.resolve("pinning.jfr");
            rec.dump(jfr);
            rec.close();
            summarizeJfr(jfr);
            System.out.println("RESULT unrelatedVirtualThreadStarved=" + !probeInTime + " probeMs=" + probeMs);
        }
    }

    /** Polls until every thread is BLOCKED/WAITING/TIMED_WAITING (or timeout); returns the state histogram. */
    static Map<Thread.State, Integer> waitForParked(List<Thread> ts, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<Thread.State, Integer> h;
        do {
            h = new TreeMap<>();
            boolean all = true;
            for (Thread t : ts) {
                Thread.State s = t.getState();
                h.merge(s, 1, Integer::sum);
                if (s != Thread.State.BLOCKED && s != Thread.State.WAITING && s != Thread.State.TIMED_WAITING) all = false;
            }
            if (all) return h;
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        return h;
    }

    static void jcmd(long pid, Path outFile, String... cmd) throws Exception {
        List<String> c = new ArrayList<>(List.of("jcmd", String.valueOf(pid)));
        c.addAll(List.of(cmd));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).redirectOutput(outFile.toFile()).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); System.out.println("STEP jcmd timeout: " + c); }
        else System.out.println("STEP jcmd " + cmd[0] + " rc=" + p.exitValue());
    }

    static void summarizeJfr(Path jfr) throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> pinnedTops = new TreeMap<>();
        for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
            String n = e.getEventType().getName();
            counts.merge(n, 1, Integer::sum);
            if (n.equals("jdk.VirtualThreadPinned") && e.getStackTrace() != null) {
                String top = e.getStackTrace().getFrames().stream().map(CacheStallRepro::fmt)
                        .filter(f -> f.contains("ConcurrentHashMap.compute") || f.contains("CacheStallRepro") || f.contains("SeckillEventCache"))
                        .limit(2).reduce((x, y) -> x + " <- " + y).orElse("?");
                pinnedTops.merge(top + " (" + e.getDuration().toMillis() + " ms)", 1, Integer::sum);
            }
        }
        System.out.println("JFR events " + counts);
        pinnedTops.forEach((k, v) -> System.out.println("JFR pinned x" + v + ": " + k));
    }

    static String fmt(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName() + ":" + f.getLineNumber();
    }
}
