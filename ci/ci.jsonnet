local build = import 'build.json';

local job(name, run, matrix={}) = {
  "runs-on": "ubuntu-latest",
  "steps": [
    {uses: "actions/checkout@v1"},
    {uses: "olafurpg/setup-scala@v2"},
    { name: name, run: run },
  ]
} + matrix;

[{
  name: "CI",
  on: ["push", "pull_request"],
  jobs: {
    Scalafmt: job("Formatting", "./bin/scalafmt --test"),
    Scalafix: job("Linting", "csbt scalafixCheck"),
    Docusaurus: job("Website", "csbt docs/docusaurusCreateSite"),
    PresentationCompiler: job( "Run tests", "csbt ++${{  matrix.scala }} cross/test", {
      matrix: { scala: build.scalaVersions, }
    }),
    UnitTests: job( "Run tests", "csbt 'unit/testOnly -- tests.${{ matrix.test }}'", {
      matrix: { test: build.tests, }
    })
  },
}]