---
id: updating-website
title: Contributing to the website
---

This website is built using [Docusaurus](https://docusaurus.io/).

For simple changes to the documentation, click on the `Edit` button at the top
of each page and submit those changes directly on GitHub.

## Running the site locally

For running the website locally, you'll need:

- `yarn` (https://yarnpkg.com/lang/en/docs/install-ci/)
- `sbt` (https://www.scala-sbt.org/1.0/docs/Setup.html)

In addition to Docusaurus, we preprocess the markdown files using:

- [sbt-docusaurus](https://olafurpg.github.io/sbt-docusaurus/), to publish the
  Docusaurus page to GitHub from the sbt shell.
- [mdoc](https://github.com/olafurpg/mdoc), to type-check and interpret Scala
  code fences.

The first step is then to preprocess the markdown files. You can do it with:

```sh
sbt
docs/run -w
```

This command will watch for new changes for Markdown files in the `docs/`
directory and regenerate the files when they change.

You can now build and launch the website using these commands:

```sh
cd website
yarn install # only the first time, to install the dependencies
yarn start
```

Now visit http://localhost:3000 and you should see a local version of the
website. New changes should trigger a reload in the browser.

## Adding a new page

Whenever you add a new markdown page to the documentation, you'll have to
manually include it in the side menu.

You can do this by editing the `website/sidebars.json` file. The name to use is
the `id` specified in the page metadata (see the existing pages for an example).
