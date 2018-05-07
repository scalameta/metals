"use strict";

import * as path from "path";
import * as cp from 'child_process';
import { workspace, ExtensionContext, window, commands } from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
  RevealOutputChannelOn,
  ExecuteCommandRequest
} from "vscode-languageclient";
import { exec } from "child_process";

export async function activate(context: ExtensionContext) {
  // The debug options for the server
  const debugOptions = [
    "-Xdebug",
    "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000,quiet=y"
  ];

  const coursierPath = path.join(context.extensionPath, "./coursier");

  const serverVersion = workspace.getConfiguration("metals").get("serverVersion")

  const javaArgs = [
    `-XX:+UseG1GC`,
    `-XX:+UseStringDeduplication`,
    "-jar", coursierPath
  ]

  const artifact = `org.scalameta:metals_2.12:${serverVersion}`

  //Validate the serverVersion resolves OK before attempting to launch it
  const coursierResolveArgs = [
      "resolve",
      "-r", "bintray:scalameta/maven",
      artifact,
    ];

  const resolveArgs = javaArgs.concat(coursierResolveArgs)

  const coursierLaunchArgs = [
    "launch",
    "-r", "bintray:scalameta/maven",
    `org.scalameta:metals_2.12:${serverVersion}`,
    "-M", "scala.meta.metals.Main"
  ];

  const launchArgs = javaArgs.concat(coursierLaunchArgs);

  const serverOptions: ServerOptions = {
    run: { command: "java", args: launchArgs },
    debug: { command: "java", args: debugOptions.concat(launchArgs) }
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: ["scala"],
    synchronize: {
      fileEvents: [
        workspace.createFileSystemWatcher("**/*.semanticdb"),
        workspace.createFileSystemWatcher("**/.metals/buildinfo/**/*.properties"),
        workspace.createFileSystemWatcher("**/project/target/active.json")
      ],
      configurationSection: 'metals'
    },
    revealOutputChannelOn: RevealOutputChannelOn.Never
  };

  const client = new LanguageClient(
    'metals',
    'Metals',
    serverOptions,
    clientOptions
  );

  const restartServerCommand = commands.registerCommand(
    'metals.restartServer',
    async () => {
      const serverPid = client["_serverProcess"].pid;
      await exec(`kill ${serverPid}`);
      const showLogsAction = "Show server logs";
      const selectedAction = await window.showInformationMessage(
        'Metals Language Server killed, it should restart in a few seconds',
        showLogsAction
      );

      if (selectedAction === showLogsAction) {
        client.outputChannel.show(true);
      }
    }
  );

  client.onReady().then(() => {
    const clearIndexCacheCommand = commands.registerCommand(
      'metals.clearIndexCache',
      async () => {
        return client.sendRequest(ExecuteCommandRequest.type, {
          command: "clearIndexCache"
        });
      }
    );
    const resetPresentationCompiler = commands.registerCommand(
      'metals.resetPresentationCompiler',
      async () => {
        return client.sendRequest(ExecuteCommandRequest.type, {
          command: "resetPresentationCompiler"
        });
      }
    );
    const sbtConnectCommand = commands.registerCommand(
      'metals.sbtConnect',
      async () => {
        return client.sendRequest(ExecuteCommandRequest.type, {
          command: "sbtConnect"
        });
      }
    );
    context.subscriptions.push(
      clearIndexCacheCommand,
      resetPresentationCompiler,
      sbtConnectCommand
    );
  });


  cp.spawn('java', resolveArgs)
    .on('exit', function(code) {
      if (code !== 0) {
        console.error(
          `Could not find Metals server artifact, ensure that metals.serverVersion setting is correct
           Coursier resolve failed on:${artifact} with exit code:${code}.`
        );
      } else {
        console.log(`Successfully resolved Metals server artifact: ${artifact}. Starting LanguageClient.`);
        context.subscriptions.push(client.start(), restartServerCommand);
      }
    });

}
