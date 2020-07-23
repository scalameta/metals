---
author: Chris Kipp
title: A Dive into Configuring Metals
authorURL: https://twitter.com/ckipp01
authorImageURL: https://avatars1.githubusercontent.com/u/13974112?s=400&u=7b6a2ddab8eec6f99e4e40ae9b81f71cb5ba92e5&v=4
---

As of this last Metals release, it's now 100% possible to fully configure Metals
without any need to pass in server properties. Depending on your editor of
choice, the process to configure Metals may be completely abstracted away. You
simply click install, wait a bit, and start coding. In this post I'd like to
talk a bit about the progression of how Metals was originally configured fully
with server properties and how it can now be fully configured via the client,
which in [LSP](https://microsoft.github.io/language-server-protocol/) terms is
your editor. This can serve both as a guide for those client extension
maintainers out there and also those curious at how Metals correctly works for
all the various editors.

## The first configuration

Looking back to the Fall of 2018, you see a giant glimpse of Metals becoming
what it is today when looking at a giant commit by
[@olafurpg](https://twitter.com/olafurpg) with the title [Implement pretty
basic language server and build
client.](https://github.com/scalameta/metals/commit/df6b41acaad1978ffd1fa25c41909c38425932ab).
It's a pretty fascinating commit to look at if you're interested in the
beginnings of Metals, but I want to focus in on a specific file that still exists
today, which is the
[MetalsServerConfig.scala](https://github.com/scalameta/metals/commit/df6b41acaad1978ffd1fa25c41909c38425932ab#diff-dc72b5c684177c884881164ab17182eb).
In this file you see the first configuration options that existed for Metals.
You see things like `isLogShowMessage` to ensure users were correctly getting
status messages instead of everything just going into the logs. (This was also
before
[`metals/status`](https://scalameta.org/metals/docs/editors/new-editor.html#metalsstatus)
existed which is used today for a better status experience in Metals). You also
see other options like `isHttpEnabled` for Metals to start the Doctor for those
that needed an HTTP client interface, or even an `icons` setting to ensure
things looked nice and matched your client. At this point, instead of just
having the user specify every one of these when they bootstrapped the server, a
`metals.client` property was introduced that we could give editors a set of
defaults. Here is an example for the first settings for Vim and Metals using the
[vim-lsc](https://github.com/natebosch/vim-lsc) plugin:

```scala
System.getProperty("metals.client", "unknown") match {
  case "vim-lsc" =>
    MetalsServerConfig().copy(
      isExtensionsEnabled = false,
      fileWatcher = FileWatcherConfig.auto,
      isLogStatusBar = true,
      isNoInitialized = true,
      isHttpEnabled = true,
      icons = Icons.unicode
    )
  ...
```
The property would then be set when the user would bootstrap Metals. This
started out as a manual process for almost all the editors utilizing
[Coursier](https://github.com/coursier/coursier). This still actually remains a
valid way to configure Metals, although not recommended if your client supports
setting `InitializationOptions` in the
[`initalize`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize)
request. It's also almost the identical process that happens behind the scenes
when client extensions like
[metals-vscode](https://github.com/scalameta/metals-vscode),
[coc-metals](https://github.com/scalameta/coc-metals), and
[metals-sublime](https://github.com/scalameta/metals-sublime) bootstrap the
server for you. For example, here is how you would manually do this:

```sh
coursier bootstrap \
  --java-opt -Xss4m \
  --java-opt -Xms100m \
  --java-opt -Dmetals.client=emacs \
  org.scalameta:metals_2.12:0.9.2 \
  -r bintray:scalacenter/releases \
  -r sonatype:snapshots \
  -o /usr/local/bin/metals-emacs -f
```

In the above example, you would then get the defaults specified in
`MetalsServerConfig.scala` for `emacs`. Again, when the process is automated
it's very similar, and you can see this if you poke around the
[`fetchAndLaunchMetals`](https://github.com/scalameta/metals-vscode/blob/master/src/extension.ts#L166)
function in the VS-Code extension. You can see how the path to Coursier is
grabbed, your `JAVA_HOME` is captured, and how we get some extra
variables/properties to call Metals with.

## User configuration

Apart from server properties, it was also necessary for users to be able to
easily change a setting, even while in the editor. For example, we have a
current setting `metals.superMethodLensesEnabled` which when enabled will
display a code lens that when invoked will either go to the parent class
containing the definition of the method or symbol or display the full method
hierarchy allowing you to choose where to go. 

Here is an example of what this looks like in Vim:
![Super Method Hierarchy](https://i.imgur.com/rEvhzG1.png)

This feature is actually turned off by default since in very large code bases
you may experience a lag. So if a user wanted to turn this on, it wouldn't be a
great user experience to have to re-bootstrap the server to enable this feature.
This is where the User Configuration comes into play by being able to change a
configuration value and notify the server via
[`workspace/didChangeConfiguration`](https://microsoft.github.io/language-server-protocol/specification#workspace_didChangeConfiguration).
This can fully happen for most of the user configuration values without any need
to restart the server. You can see the first configuration options added this
way in [this
commit](https://github.com/scalameta/metals/commit/f4706ec75afb9bf797e3144f4a0e91bb0b186e07)
where the ability to define your `JAVA_HOME` was added. With now allowing for
user configurations in Metals, this allowed for an even more customized
experience.

## Experimental

Being able to customize the server with properties and allowing users to pass in
some configuration values worked great. However, once Metals started creating
LSP extensions for functionality that wasn't supported fully just by LSP, then a
way was needed for the client to express that it supported these extensions.
This is when Metals started to use the
[`ClientCapabilities.experimental`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize)
field which the client needed to declare support the extension. You can see the
first inklings of this when the [Tree View
Protocol](https://scalameta.org/metals/docs/editors/tree-view-protocol.html) was
introduced [here in this
commit](https://github.com/scalameta/metals/commit/a55a2413ef10237c8510eb707c0de0cd03b83d85#diff-f8c05eebbf12c9c21a7d568f09b500ea).
This then continued to be further expanded as we introduced more extensions.

As it became easier for various clients to set this, we slowly [started to
migrate](https://github.com/scalameta/metals/pull/1414) other options that could
only be previously set via server properties to
`ClientCapabilities.experimental`. So settings like which format you'd like the
Doctor to return could now be set directly by the client without need to
bootstrap the server with a specific property. This allowed for much easier
configuration than was previously had.

## InitializationOptions

Once it was clear that configuring Metals via the client was desirable, a closer
look was taken at `InitializationOptions` that can be passed in during the
[`initialize`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#initialize)
request. Since any value is able to be passed in this way, a decision was made
to fully migrate all the possible settings that were previously set as server
properties (except a select few that we'll touch on later), and also move all
the of settings that could be set under `experimental` to
`InitializationOptions` as well. This ultimately allows for clients to fully
configured Metals via `InitializationOptions` without the need to set any server
properties. In theory this also meant that you could not use the same Metals
executable for VS Code, Vim, or Emacs since the server is fully being configured
by the client itself. The current settings that can be passed in and their
defaults are explained in detail [here on the
website](https://scalameta.org/metals/docs/editors/new-editor.html#initializationoptions),
but the interface is as follows:

```typescript
interface MetalsInitializationOptions {
  compilerOptions?: CompilerInitializationOptions;
  debuggingProvider?: boolean;
  decorationProvider?: boolean;
  didFocusProvider?: boolean;
  doctorProvider?: "json" | "html";
  executeClientCommandProvider?: boolean;
  globSyntax?: "vscode" | "uri";
  icons?: "vscode" | "octicons" | "atom" | "unicode";
  inputBoxProvider?: boolean;
  isExitOnShutdown?: boolean;
  isHttpEnabled?: boolean;
  openFilesOnRenameProvider?: boolean;
  quickPickProvider?: boolean;
  renameFileThreshold?: number;
  slowTaskProvider?: boolean;
  statusBarProvider?: "on" | "off" | "log-message" | "show-message";
  treeViewProvider?: boolean;
  openNewWindowProvider?: boolean;
}
```
```typescript
interface CompilerInitializationOptions {
  completionCommand?: string;
  isCompletionItemDetailEnabled?: boolean;
  isCompletionItemDocumentationEnabled?: boolean;
  isCompletionItemResolve?: boolean;
  isHoverDocumentationEnabled?: boolean;
  isSignatureHelpDocumentationEnabled?: boolean;
  overrideDefFormat?: "ascii" | "unicode";
  parameterHintsCommand?: string;
  snippetAutoIndent?: boolean;
}
```

You'll notice that this allows for a much finer grained configuration if the
client chooses to set certain values. Everything from whether or not the Scala
Presentation Compiler should populate the `SignatureHelp.documentation` to
whether or not the editor supports opening a new window after using the
`metals.new-scala-project` command can now be easily configured. Fully
configuring Metals through `InitializationOptions` is now the recommended way to
configure Metals.

## Are there still server properties?

While all of the old server properties still exist for Metals, it's no longer
recommended to use them to configure Metals. However, there are still a few
server properties that remain only server properties since they are not meant to
be widely used, and aren't exactly recommended to use for the average user. You
can see an up to date list of these [here on the
website](https://scalameta.org/metals/docs/editors/new-editor.html#metals-server-properties)
and what functionality they provide.

## Conclusion

As of Metals 0.9.2 it's fully possibly for all clients to use a default
bootstrapped Metals that can fully be configured via `InitializationOptions`.
There is a freshly updated [Integrating a new
editor](https://scalameta.org/metals/docs/editors/new-editor.html) section on
the website to help explain how to exactly configure a client for usage with
Metals. As always, don't hesitate to reach out on any of the various channels
located in the footer or submit an issue to either improve documentation or to
log a bug. Also as a reminder, there is a separate repo for
[metals-feature-requests](https://github.com/scalameta/metals-feature-requests).


Happy coding with Metals!
