---
id: online-ides
title: Online IDEs
---

Metals can also be installed in some online environments, which enable users to
work on their code inside of a browser. This can be a good option when users need
to set up their workspace quickly or are lacking appropriate resources on their
local machine.

# Gitpod

One of such online environments is Gitpod, which can be used directly in your
browser as well as shared with others without the need to explicitly set up the
tools and settings needed to run Scala project with Metals. You can read more
about Gitpod [here](https://www.gitpod.io/docs/).

![example](https://imgur.com/2AiIN43.gif)

## Example Gitpod repository for Metals

An example repository, which is already set up and integrated with Gitpod, can
be found [here](https://github.com/scalameta/metals-gitpod-sample). The project
contains, out of the box, everything that you need to start experimenting with
Metals in an online environment similar to Visual Studio Code. The only thing
that you need to do to set up the environment by yourself is to open the
[link](https://gitpod.io/#https://github.com/scalameta/gitpod-sample) provided
in the README of the project. The import prompt for Metals will show up,
however, in this case, _.bloop_ files normally generated during import were
created in the gitpod init script, so import will not be needed.

The moment that the import is completed, you will be able to run the code in
_Main.scala_ and tests in _SampleTest.scala_ (using code lenses). You can also
execute code using _Worksheet.worksheet.sc_.

If you want to set up your own Gitpod project that already comes with all the
necessary tools to run the Scala project with Metals, you can fork and customize
this repository.

Gitpod environment can be set up from scratch with the
[setup assistant](https://www.gitpod.io/docs/configuration/). It helps you to
create scripts that are then used by Gitpod to make fresh instances of the
environment. These scripts are for example:

- **prebuild** that can be used to set up the environment on commit push even
  before opening it
- **init** invoked when the workspace is opened for the first time
- **command** run every time when the workspace is run again after being stopped

The Gitpod configuration is located under _.gitpod.yml_. The minimal Gitpod
setup requires in our case Scala and Metals extensions to be provided which is
already done in the example project.

Gitpod itself is based on the [Eclipse Theia project](https://theia-ide.org/),
an editor which can be easily customized according to your needs and is backed
by the [Eclipse Foundation](https://www.eclipse.org/org/foundation/). The Metals
plugin will also work in an environment created within
[Eclipse Che](https://www.eclipse.org/che/), which is a complementary project to
Theia that helps companies and users create their own online workspace
infrastructure. These topics should be especially interesting for larger
companies that might want to increase their productivity by improving their
developers' experience. With some additional setup it's possible using those
tools to have a zero startup environments.

## Open VSX

Metals extension is available in the [Open VSX Registry](https://open-vsx.org/)
which is used to provide extensions in Gitpod.  
You can find the newest version of Metals in there.
