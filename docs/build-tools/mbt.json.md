# MBT

Metals V2 uses a file called `mbt.json` to give the language server all the information it needs to understand a code base. It holds the minimal amount of information that lets the LS see the same picture as it would during the build of the code base. This allows the LS to correctly flag errors, refactor code or navigate to references or definitions precisely.
`mbt.json` files are currently dependent on the location of the workspace. Therefore they generally need to be generated for each user. The format, however, is designed such that we could work towards mbt.json files you can check into a repository in the future.
Importers for various build systems exist to create mbt.json files from build files for maven, gradle, bazel or others. The language server does not look at the build files directly, it only ever works with `mbt.json`. So you could even write an mbt.json file by hand. Importers for Maven, Gradle and Bazel are built into Metals V2.

The schema of mbt.json is describe in this [schema](./mbt.schema.json)
