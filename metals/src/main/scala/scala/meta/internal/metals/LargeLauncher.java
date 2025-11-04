package scala.meta.internal.metals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.ConcurrentMessageProcessor;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;

public class LargeLauncher<T> {
  private LargeLauncher() {}

  public static class Builder<T> extends Launcher.Builder<T> {
    // Just a copy of the original create method, but with the LargeStreamMessageProducer
    @Override
    public Launcher<T> create() {
      // Validate input
      if (input == null) throw new IllegalStateException("Input stream must be configured.");
      if (output == null) throw new IllegalStateException("Output stream must be configured.");
      if (localServices == null)
        throw new IllegalStateException("Local service must be configured.");
      if (remoteInterfaces == null)
        throw new IllegalStateException("Remote interface must be configured.");

      // Create the JSON handler, remote endpoint and remote proxy
      MessageJsonHandler jsonHandler = createJsonHandler();
      if (messageTracer != null) {
        messageTracer.setJsonHandler(jsonHandler);
      }
      RemoteEndpoint remoteEndpoint = createRemoteEndpoint(jsonHandler);
      T remoteProxy = createProxy(remoteEndpoint);

      // Create the message processor
      LargeStreamMessageProducer reader =
          new LargeStreamMessageProducer(input, jsonHandler, remoteEndpoint);
      MessageConsumer messageConsumer = wrapMessageConsumer(remoteEndpoint);
      ConcurrentMessageProcessor msgProcessor =
          createMessageProcessor(reader, messageConsumer, remoteProxy);
      ExecutorService execService =
          executorService != null ? executorService : Executors.newCachedThreadPool();
      return createLauncher(execService, remoteProxy, remoteEndpoint, msgProcessor);
    }
  }
}
