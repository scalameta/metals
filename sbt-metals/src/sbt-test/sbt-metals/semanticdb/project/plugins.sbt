val pluginVersion = sys.props
  .get("plugin.version")
  .get

addSbtPlugin("org.scalameta" % "sbt-metals" % pluginVersion)
