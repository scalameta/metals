def defaultPattern = "%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n"

appender("STDOUT", ch.qos.logback.core.ConsoleAppender) {
  encoder(PatternLayoutEncoder) {
    pattern = defaultPattern
  }
}

appender("LSP", scala.meta.languageserver.LSPLogger) {
  encoder(PatternLayoutEncoder) {
    pattern = defaultPattern
  }
}

root(DEBUG, ["LSP", "STDOUT"])
logger("langserver.core.MessageWriter", INFO, ["LSP", "STDOUT"])
logger("langserver.core.MessageReader", INFO, ["LSP", "STDOUT"])
