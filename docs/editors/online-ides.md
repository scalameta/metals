---
id: online-ides
title: Online IDEs
---

# Gitpod
Aside from locally installed editors, Metals can be used in online development environments. One of them is Gitpod, 
in which you can create the code editor setup that can be used directly in your browser 
as well as shared with others without a need to explicitly set up the tools and settings required
to run Scala project with Metals. We are going to provide the example setup for Metals with such an environment based on
the Gitpod platform. You can read more about Gitpod [here](https://www.gitpod.io/docs/).

## Example Gitpod repository for Metals
The repository which is already set up and integrated with Gitpod can be found 
[here](https://github.com/scalameta/metals-gitpod-sample). The project contains, out of the box,
everything that you need to start experimenting with Metals in an online environment similar to Visual Studio Code. 
The only thing that you need to do to set up the environment by yourself is to open the
[link](https://gitpod.io/#https://github.com/scalameta/gitpod-sample) provided in the README of the project.
The import prompt for Metals will show up (however, in general, it wouldn't be needed if the *.bloop* files existed).

The moment that the import is completed, you will be able to run the code in *Main.scala* and tests in *SampleTest.scala*
(using code lenses). You can also execute code using *Worksheet.worksheet.sc*.

If you want to set up your own Gitpod project that already comes with all the necessary tools
to run the Scala project with Metals, you can clone and customize this repository.

Gitpod environment can be set up from scratch with the [setup assistant](https://www.gitpod.io/docs/configuration/).
It helps you to create scripts that are then used by Gitpod to make fresh instances of the environment. These scripts are
for example: 
- **prebuild** that can be used to set up the environment on commit push even before opening it 
- **init** invoked when the workspace is opened for the first time
- **command** run every time when the workspace is run again after being stopped  

The Gitpod configuration is located under *.gitpod.yml*. The minimal Gitpod setup requires in our case Scala and Metals 
extensions to be provided which is already done in the example project.    

## Open VSX
Metals extension is available in the [Open VSX Registry](https://open-vsx.org/) which is used to provide extensions in Gitpod.  
You can find the newest version of Metals in there.
