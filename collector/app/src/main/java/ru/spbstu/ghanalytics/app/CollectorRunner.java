package ru.spbstu.ghanalytics.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.Collector;

import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs one collector chosen by {@code --source}. All {@link Collector} beans from every module
 * are found by component scanning; their {@code source()} names must be unique.
 * <p>
 * The run journal ({@code core.collection_runs}) is written by the collector itself,
 * because only it knows the run id and the number of rows.
 */
@Component
public class CollectorRunner implements ApplicationRunner, ExitCodeGenerator {

    static final int OK = 0;
    static final int COLLECTOR_FAILED = 1;
    static final int INVALID_ARGUMENTS = 2;

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    private final Map<String, Collector> collectors;
    private final Clock clock;
    private final PrintStream out;
    private int exitCode = OK;

    @Autowired // two constructors: tell Spring which one to use
    public CollectorRunner(List<Collector> collectors) {
        this(collectors, Clock.systemDefaultZone(), System.out);
    }

    CollectorRunner(List<Collector> collectors, Clock clock, PrintStream out) {
        this.collectors = bySource(collectors);
        this.clock = clock;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!CollectorArgs.hasSource(args)) {
            printUsage();
            exitCode = OK;
            return;
        }

        CollectorArgs parsed;
        try {
            parsed = CollectorArgs.parse(args, LocalDate.now(clock));
        } catch (InvalidArgumentsException e) {
            log.error("invalid arguments: {}", e.getMessage());
            printUsage();
            exitCode = INVALID_ARGUMENTS;
            return;
        }

        Collector collector = collectors.get(parsed.source());
        if (collector == null) {
            log.error("unknown source '{}', available: {}", parsed.source(), collectors.keySet());
            exitCode = INVALID_ARGUMENTS;
            return;
        }

        log.info("running {} with {}", parsed.source(), parsed.context());
        try {
            collector.collect(parsed.context());
            log.info("{} finished", parsed.source());
            exitCode = OK;
        } catch (RuntimeException e) {
            log.error("{} failed", parsed.source(), e);
            exitCode = COLLECTOR_FAILED;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void printUsage() {
        out.println(CollectorArgs.USAGE);
        out.println("Available sources: " + (collectors.isEmpty() ? "(none yet)" : String.join(", ", collectors.keySet())));
    }

    private static Map<String, Collector> bySource(List<Collector> collectors) {
        Map<String, Collector> map = new TreeMap<>();
        for (Collector collector : collectors) {
            Collector previous = map.put(collector.source(), collector);
            if (previous != null) {
                throw new IllegalStateException("two collectors use source '" + collector.source() + "': "
                        + previous.getClass().getName() + " and " + collector.getClass().getName());
            }
        }
        return map;
    }
}
