package tests

class CustomLoggingFixture(fn: scribe.Logger => scribe.Logger)
    extends munit.Fixture[Unit]("custom-logging") {
  def apply(): Unit = ()
  override def beforeAll(): Unit = {
    fn(scribe.Logger.root).replace()
  }
  override def afterAll(): Unit = {
    scribe.Logger.root.reset()
  }
}

object CustomLoggingFixture {
  def defaults(): CustomLoggingFixture = new CustomLoggingFixture(identity)
  def showDebug(): CustomLoggingFixture = new CustomLoggingFixture(fn =>
    fn.clearHandlers().withHandler(minimumLevel = Some(scribe.Level.Debug))
  )
  def showWarnings(): CustomLoggingFixture = new CustomLoggingFixture(fn =>
    fn.clearHandlers().withHandler(minimumLevel = Some(scribe.Level.Warn))
  )
}
