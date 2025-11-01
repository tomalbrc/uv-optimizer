package de.tomalbrc;

import de.tomalbrc.optialg.Optimizer;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        long startAll = System.nanoTime();
        long deleteDuration = 0;
        long copyDuration = 0;
        long optimizeDuration = 0;

        try {
            System.out.println("Copying resourcepack...");
            if (output.toFile().exists()) {
                long t0 = System.nanoTime();
                FileUtils.deleteDirectory(output.toFile());
                deleteDuration = System.nanoTime() - t0;
            }

            long t1 = System.nanoTime();
            FileUtils.copyDirectory(input.toFile(), output.toFile());
            copyDuration = System.nanoTime() - t1;

            ResourcePack resourcePack = new ResourcePack(input);
            Optimizer optimizer = new Optimizer(resourcePack, output);

            long t2 = System.nanoTime();
            optimizer.optimize();
            optimizeDuration = System.nanoTime() - t2;
        } finally {
            long total = System.nanoTime() - startAll;
            System.out.println("Timings:");
            if (deleteDuration > 0) {
                System.out.println("  delete:   " + formatNanos(deleteDuration));
            } else {
                System.out.println("  delete:   skipped");
            }
            System.out.println("  copy:     " + formatNanos(copyDuration));
            System.out.println("  optimize: " + formatNanos(optimizeDuration));
            System.out.println("  total:    " + formatNanos(total));
        }
    }

    private static String formatNanos(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        long millis = nanos / 1_000_000;
        return String.format("%dms (%.1fs)", millis, seconds);
    }
}
