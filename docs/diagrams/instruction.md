### Getting started

- Install plantuml `cs install --contrib plantuml`
- vscode has extension which allows to preview creating diagram - [PlantUML](https://marketplace.visualstudio.com/items?itemName=jebbs.plantuml)

### Usage

Documentation can be found on the [website](https://plantuml.com/command-line).

The most basic way to run it is `plantuml file1 file2 file3`. This will look for @startXYZ into file1, file2 and file3. For each diagram, a .png file will be created.

For processing a whole directory, you can use:

`plantum "c:/directory1" "c:/directory2"`
This command will search for @startXYZ and @endXYZ in `.pu` files of the `c:/directory1` and `c:/directory2` directories.

In terms of Metals docs layout one can run:

- `plantuml ./docs/diagrams ./docs/diagrams/out` from workspace
- `plantuml . -o ./out/` from `workspace/docs/diagrams`

to regenerate diagrams.
