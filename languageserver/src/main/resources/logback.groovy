appender("FILE", FileAppender) {
  file = "scala-langserver.log"
  append = false
  encoder(PatternLayoutEncoder) {
    pattern = "%level %logger - %msg%n"
  }
}

root(DEBUG, ["FILE"])