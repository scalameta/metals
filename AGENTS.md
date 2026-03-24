# Scala projects

* ALWAYS use Metals MCP tools to compile and run tests instead of relying on bash commands
* If MCP tools are not available report that to the user
* after adding a dependency to `build.sbt`, ALWAYS run the `import-build` tool
* to lookup a dependency or the latest version, use the `find-dep` tool
* to lookup the API of a class, use the `inspect` tool
* use `sbt --client` instead of `sbt` to connect to a running sbt server for
  faster execution
* to verify that the app starts use `sbt run`, WITHOUT `--client`, as it
  prevents interrupting the process
* before committing, ALWAYS format all changed Scala files using `./bin/scalafmt` 
* NEVER use non-local returns

# Git

* always create new commits, instead of amending existing ones