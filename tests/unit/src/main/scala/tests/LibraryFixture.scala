package tests

class LibraryFixture(name: String, doFetch: () => Library)
    extends munit.Fixture[Library](name) {
  private var lib: Library = _
  override def beforeAll(): Unit = {
    lib = doFetch()
  }
  override def afterAll(): Unit = {
    lib = null
  }
  override def apply(): Library = lib
}
