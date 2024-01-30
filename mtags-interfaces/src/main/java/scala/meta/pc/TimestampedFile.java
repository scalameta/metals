package scala.meta.pc;

import java.io.File;
import java.nio.file.Path;

public class TimestampedFile {
    public File file;
    public long timestamp;

    public TimestampedFile(File file, long timestamp) {
        this.file = file;
        this.timestamp = timestamp;
    }

    public Path toPath() {
      return file.toPath();
    }

    public String name() {
      return file.getName();
    }
}
