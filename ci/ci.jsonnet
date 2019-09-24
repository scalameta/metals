local build = import 'build.json';

local job(steps) = {
  "runs-on": "ubuntu-latest",
  "steps": [
    {uses: "actions/checkout@v1"},
    {uses: "olafurpg/setup-scala@v2"},
  ] + steps,
};

local step(name,run) = {
  name: name,
  run: run
};

[{
  name: "CI",
  on: ["push", "pull_request"],
  jobs: {
    Scalafmt: job([
      step("Formatting", "./bin/scalafmt --test")
    ])
  },
}]