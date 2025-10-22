---
authors: ckipp
title: A Metals Retrospective (Part 1)
---

![metals-banner](https://github.com/scalameta/gh-pages-images/blob/master/metals/2021-02-02-metals-retro-part1/FYZXteD.png?raw=true)

At the end of 2020 the Metals team sent out a survey to gather input from our
users in hopes to get a better picture of who you are, what you want out of
Metals, and any other useful feedback you may have wanted to provide. With just
under 400 responses we got a ton of great data, some interesting insights, and a
nice picture of what is currently hindering users, and what common functionality
continually comes up as feature requests. In order to best use this data, and
also to share some results, we thought it'd be a good idea to go over some of
the sections, address some of the points that came up, and also clear up any
misconceptions that may have appeared in the survey results. The idea is for
this to be a 2 part series. You can expect the following:

- Part 1 - Where we discuss some of the initial results and address some common
  misconceptions and questions.
- Part 2 - Where we go deeper into the desired features, the biggest pain
  points, and our plans for this next year.

## Editor Support

![editor-results](https://github.com/scalameta/gh-pages-images/blob/master/metals/2021-02-02-metals-retro-part1/w67gMGW.png?raw=true)

Surprising no one, VS Code came out on top for editors with the most desire for
Metals support. Our
[VS Code extension](https://github.com/scalameta/metals-vscode) has over 100k
downloads and it is safe to say that it offers the best support for overall
Metals usage. The next most popular of our extensions is probably `coc-metals`
which had just over 20k downloads in 2020. There are certain features that do
only work in VS Code mainly due to the robustness of the LSP support and other
extra features. Here are a few examples of features that we added this past year
that work extremely well with VS Code with no hardly no extra effort on the
users part:

- Debugging support - Early on in 2020 Metals v0.8.0 added debugging support.
  Now with the click of a button you can run, test, and debug your code right
  from inside VS Code.
- Preview renames - in Metals v0.8.1 functionality was added so that you can now
  preview the changes after triggering a rename for up to 300 files.
- Analyze stacktrace functionality - As of Metals v0.9.4 Metals gained the
  ability to take a stack trace and give you a nice view of the entire stack
  with clickable links to go to that part of your code. This is all found in the
  very useful web view of VS Code.
- Show implicits and type decorations - As of Metals v0.9.5 Metals gained the
  ability to show implicits and type decorations as decorations directly inline
  of your code.
- Show implicits conversion and classes - As of Metals v0.9.6 Metals gained the
  ability to show implicits conversion and classes as decorations directly
  inline of your code.
- Navigativing stacktrace while debugging - Also in Metals v0.9.6 the ability to
  navigate stacktraces during a deubugging sesions was added, which re-used the
  functionality introduced in v0.9.4 to analyze stacktraces.

Now at this point you may be starting to assume that we heavily favor VS Code
and that Metals is geared towards it. We got a few comments related to this
throughout the survey. However, I want to make a specific point that since the
inception of Metals, the early maintainers did a fantastic job of ensuring that
everything would work in clients that offered the necessary LSP support, and
also extra features to clients that offered support for the Metals specific LSP
extensions that we use. This has remained core for us a we continue to provide
new features. Pretty much any new feature that is added is added in a way that
can also be utilized for editors that may not have the same level of support as
VS Code. Here are a few examples:

- Debugging support - Metals actually offers deubugging support for any client
  that can serve as a DAP client. So for example using `coc-metals` you can also
  run, test, and debug your code while utilizing
  [`vimspector`](https://github.com/puremourning/vimspector). The same is true
  for emacs clients that are utilizing
  [`lsp-mode`](https://github.com/emacs-lsp/lsp-mode).
- Analyze stacktrace functionality - For clients that don't have a web view,
  this feature is still supported by producing a file with code lens' in it to
  allow you to navigate to the relevant parts of the stacktrace.
- Implicits, type decorations, implicits conversions and classes - VS Code isn't
  the only extension that actually implements this. There is also
  [`metals-sublime`](https://github.com/scalameta/metals-sublime) that has
  inline decorations. For other editors that may not support inline decorations,
  this feature is still re-usable and the information is actually displayed in
  hover as another section.

So again, while VS Code is an incredible editor, that offers incredible support
for the various Metals features, we work hard to ensure that these features are
also usable in our other extensions, even if they look a bit different. While
there are varying levels of support in the various editor extensions, much of
this is due to the those editors being the editors the current maintainers use.
We are always open for more help in any of the current editor extensions and
this is actually a great way to get involved. We are also open to help you
create a new extension to maybe support an editor that is yet to have a
Metals-specific extension! Here are links to the various Metals clients:

- [scalameta/metals-vscode](https://github.com/scalameta/metals-vscode)
- [scalameta/coc-metals](https://github.com/scalameta/coc-metals)
- [scalameta/nvim-metals](https://github.com/scalameta/nvim-metals)
- [scalameta/metals-sublime](https://github.com/scalameta/metals-sublime)
- [scalameta/metals-eclipse](https://github.com/scalameta/metals-eclipse)
- [emacs-lsp/lsp-metals](https://github.com/emacs-lsp/lsp-metals)

### Will you support IntelliJ?

When asked about what editor people wished had Metals support, here were the
results:

| Editor                                               | Votes |
| ---------------------------------------------------- | ----- |
| [IntelliJ](https://www.jetbrains.com/idea/)          | 19    |
| [Notepad++](http://notepad-plus-plus.org/)           | 2     |
| [Gnome Builder](https://wiki.gnome.org/Apps/Builder) | 2     |
| [Nova](https://www.nova.app/)                        | 2     |
| [Kakoune](http://kakoune.org/)                       | 2     |
| [Netbeans](https://netbeans.org/)                    | 2     |
| [Eclipse Theia](https://theia-ide.org/)              | 1     |
| [jEdit](http://www.jedit.org/)                       | 1     |

Some of these editors will already actually work with Metals, especially if they
support VS Code extensions like Eclipse Theia does. However, one question we
often get is: "Will you support IntelliJ?" The simple answer to this question is
_No_, but not for the reason people may think. IntelliJ is a fantastic project
that is the most widely used editor in Scala. Metals aims to be a language
server for clients that implement LSP. Since IntelliJ has no _official_ support
for LSP, we have no intention on adding support for IntelliJ. Many times the
target audience is different as well.

## Why do people start using Metals

| Reason                                        | Very Important | Somewhat important | Not important |
| --------------------------------------------- | -------------- | ------------------ | ------------- |
| I wanted a lighter alternative to other IDEs. | 245            | 74                 | 27            |
| I could use my favorite editor.               | 223            | 62                 | 56            |
| I wanted more accurate compiler errors.       | 220            | 86                 | 36            |
| I wanted a fully open source solution.        | 133            | 138                | 69            |
| Metals had features other IDEs didn't         | 56             | 112                | 160           |

## Information and Help with Metals

Over this past year we've done our best to make sure everyone is aware of all
the great stuff that is happening in Metals. We have multiple channels of
communication open with pretty impressive response times if you ever get stuck.
Here are the results about where people go for question about Metals and also
where they get their news about Metals.

![metals info](https://github.com/scalameta/gh-pages-images/blob/master/metals/2021-02-02-metals-retro-part1/6Ijm9Bv.png?raw=true)
![metals help](https://github.com/scalameta/gh-pages-images/blob/master/metals/2021-02-02-metals-retro-part1/2Qysoqe.png?raw=true)

Apart from the places that we had listed in the survey, the place mentioned the
most for where people hear about Metals related news was
[Scala Times](https://scalatimes.com/)!

As a reminder, don't ever hesitate to reach out, and if anything is missing in
the docs, please lend a helping hand or point it out to us.

## I wish Metals had... but it does!

Below are some things that appeared in the survey as wishes, but that Metals
already supports, or has added support for since the survey! Hopefully these
will help highlight some lesser known features of Metals or serve as a reminder
of some of the features we have.

| Requested                                            | Status                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Renaming symbols                                     | Available since v0.8.0 and all supported editors support this.                                                                                                                                                                                                                                                                                                                                                                              |
| Better info about Ammonite scripts failing.          | Actually because of this comment, there was some work done to improve the error messages about why Ammonite may not be starting. You can see this here [in this pr](https://github.com/scalameta/metals/pull/2333).                                                                                                                                                                                                                         |
| A check telling me if everything is setup correctly. | We recently did some work or the Metals Doctor to help you see what issues are going on. You can trigger Doctor by the `run-doctor` command. It may differ a bit per client. You can see the recent changes made [here in this pr](https://github.com/scalameta/metals/pull/2339).                                                                                                                                                          |
| Seamless install in Vim                              | There are two Metals-specific Vim and Neovim extensions, both which offer an automated install feature. [coc-metals](https://github.com/scalameta/coc-metals) and [nvim-metals](https://github.com/scalameta/nvim-metals).                                                                                                                                                                                                                  |
| Navigating stacktrace feature in emacs.              | This actually should work, since it just needs code lenses to work for clients that don't have a web view. Part of this is just documentation, so if you're an emacs user, please help us with docs!                                                                                                                                                                                                                                        |
| Use a specific version of Ammonite                   | You can currently do this by setting the version in a comment on the top of a file like illustrated [here](https://scalameta.org/metals/docs/troubleshooting/faq#how-do-i-use-scala-2xx-for-my-script). Also the next release of Metals will have better support for a fallback version of the compiler that is used for standalone scripts. This will give you more control over what version is used in situations like Ammonite scripts. |

## What's next?

We want to thank you again for taking the time and filling out our year-end
survey. Keep on the lookout for Part 2 of this, and we'll be discussing the
biggest pain points and some of the most desired features that we plan to tackle
this next year. We'll also do a further look into build server and build tool
support finishing off with an update on our current Scala 3 support and the
efforts that are going into it.

Cheers,

The Metals team
