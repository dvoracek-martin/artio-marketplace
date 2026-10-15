package io.artio.marketplace.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the pinned Zot binary as a supervised child; Spring's PID 1 owns, observes and terminates
 * it.
 */
@Component
public final class ZotProcessSupervisor implements SmartLifecycle {
  private final String binary;
  private final String config;
  private volatile Process process;
  private volatile boolean running;
  private volatile boolean stopping;

  public ZotProcessSupervisor(
      @Value("${marketplace.registry.binary:}") String binary,
      @Value("${marketplace.registry.config:/etc/zot/config.json}") String config) {
    this.binary = binary;
    this.config = config;
  }

  @Override
  public synchronized void start() {
    if (binary.isBlank()) {
      running = true;
      return;
    } // local source runs can use an independently started Zot service
    try {
      process =
          new ProcessBuilder(binary, "serve", config).redirectErrorStream(true).inheritIO().start();
      running = true;
      Thread watcher =
          new Thread(
              () -> {
                try {
                  int exit = process.waitFor();
                  running = false;
                  if (!stopping) {
                    System.err.println(
                        "Zot child exited; stopping Marketplace to prevent false readiness (exit "
                            + exit
                            + ")");
                    System.exit(70);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              },
              "zot-supervisor");
      watcher.setDaemon(true);
      watcher.start();
    } catch (IOException e) {
      throw new IllegalStateException("Configured Zot process could not start", e);
    }
  }

  @Override
  public synchronized void stop() {
    stopping = true;
    running = false;
    if (process != null && process.isAlive()) {
      process.destroy();
      try {
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(3, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running && (process == null || process.isAlive());
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }
}
