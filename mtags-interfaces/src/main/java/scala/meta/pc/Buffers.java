package scala.meta.pc;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.net.URI;

public interface Buffers {
  Optional<PcAdjustFileParams> getFile(URI uri, String scalaVersion);
}
