package scala.meta.pc;

import java.time.Duration;
import java.util.Optional;

public abstract class ProgressBars {

  public interface ProgressBar {
    public String title();

    public String message();

    public long progress();

    public long total();

    public void updateProgress(long progress, long total);

    public void updateMessage(String message);
  }

  public static class ProgressTimeout {
    public final Duration duration;
    public final Runnable onTimeout;

    public ProgressTimeout(Duration duration, Runnable onTimeout) {
      this.duration = duration;
      this.onTimeout = onTimeout;
    }
  }

  public static class StartProgressBarParams {
    public final String title;
    public boolean progressEnabled = false;
    public boolean timerEnabled = true;
    public Optional<Runnable> onCancel = Optional.empty();
    public Optional<ProgressTimeout> timeout = Optional.empty();

    public StartProgressBarParams(String title) {
      this.title = title;
    }

    public StartProgressBarParams withProgress(boolean value) {
      this.progressEnabled = value;
      return this;
    }

    public StartProgressBarParams withTimer(boolean value) {
      this.timerEnabled = value;
      return this;
    }

    public StartProgressBarParams withCancelHandler(Runnable onCancel) {
      this.onCancel = Optional.of(onCancel);
      return this;
    }

    public StartProgressBarParams withTimeout(ProgressTimeout timeout) {
      this.timeout = Optional.of(timeout);
      return this;
    }
  }

  public abstract ProgressBar start(StartProgressBarParams params);

  public abstract void end(ProgressBar progressBar);

  private static final ProgressBar EMPTY_PROGRESS_BAR =
      new ProgressBar() {
        @Override
        public String title() {
          return null;
        }

        @Override
        public String message() {
          return null;
        }

        @Override
        public long progress() {
          return 0;
        }

        @Override
        public long total() {
          return 0;
        }

        @Override
        public void updateProgress(long progress, long total) {}

        @Override
        public void updateMessage(String message) {}
      };
  public static final ProgressBars EMPTY =
      new ProgressBars() {
        @Override
        public ProgressBar start(StartProgressBarParams params) {
          return EMPTY_PROGRESS_BAR;
        }

        @Override
        public void end(ProgressBar progressBar) {}
      };
}
