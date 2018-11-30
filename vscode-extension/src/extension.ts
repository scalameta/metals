"use strict";

import * as path from "path";
import {
  workspace,
  ExtensionContext,
  window,
  commands,
  StatusBarAlignment,
  ProgressLocation,
  IndentAction,
  languages,
  WebviewPanel,
  ViewColumn
} from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  RevealOutputChannelOn,
  ExecuteCommandRequest
} from "vscode-languageclient";
import { exec } from "child_process";
import { Commands } from "./commands";
import {
  MetalsSlowTask,
  MetalsStatus,
  MetalsDidFocus,
  ExecuteClientCommand
} from "./protocol";
import { type } from "os";

export async function activate(context: ExtensionContext) {
  // Make editing Scala docstrings slightly nicer.
  enableScaladocIndentation();

  const userJavaHome = workspace.getConfiguration("metals").get("javaHome");
  if (typeof userJavaHome === "string" && userJavaHome !== "") {
    startServer(context, userJavaHome);
  } else {
    require("find-java-home")((err, javaHome) => {
      if (err) window.showErrorMessage("Unable to find Java home.");
      startServer(context, javaHome);
    });
  }
}

function startServer(context: ExtensionContext, javaHome: string) {
  const javaPath = path.join(javaHome, "bin", "java");
  const coursierPath = path.join(context.extensionPath, "./coursier");

  const serverVersion = workspace
    .getConfiguration("metals")
    .get("serverVersion");

  const serverProperties: string[] = workspace
    .getConfiguration("metals")
    .get("serverProperties")
    .toString()
    .split(" ")
    .filter(e => e.length > 0);

  const javaArgs = serverProperties.concat([
    `-Dmetals.client=vscode`,
    `-Xss4m`,
    `-Xms1G`,
    `-Xmx4G`,
    `-XX:+UseG1GC`,
    `-XX:+UseStringDeduplication`,
    "-jar",
    coursierPath
  ]);

  const coursierLaunchArgs = [
    "launch",
    "-r",
    "bintray:scalacenter/releases",
    "-r",
    "sonatype:releases",
    "-r",
    "sonatype:snapshots",
    `org.scalameta:metals_2.12:${serverVersion}`,
    "-M",
    "scala.meta.metals.Main"
  ];

  const launchArgs = javaArgs.concat(coursierLaunchArgs);

  const serverOptions: ServerOptions = {
    run: { command: javaPath, args: launchArgs },
    debug: { command: javaPath, args: launchArgs }
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "scala" }],
    synchronize: {
      configurationSection: "metals"
    },
    revealOutputChannelOn: RevealOutputChannelOn.Never
  };

  const client = new LanguageClient(
    "metals",
    "Metals",
    serverOptions,
    clientOptions
  );

  context.subscriptions.push(
    commands.registerCommand("metals.restartServer", async () => {
      const serverPid = client["_serverProcess"].pid;
      await exec(`kill ${serverPid}`);
      const showLogsAction = "Show server logs";
      const selectedAction = await window.showInformationMessage(
        "Metals Language Server killed, it should restart in a few seconds",
        showLogsAction
      );

      if (selectedAction === showLogsAction) {
        client.outputChannel.show(true);
      }
    })
  );

  context.subscriptions.push(client.start());

  client.onReady().then(_ => {
    var doctor: WebviewPanel;
    function getDoctorPanel(): WebviewPanel {
      if (!doctor) {
        doctor = window.createWebviewPanel(
          "metals-doctor",
          "Metals Doctor",
          ViewColumn.Active
        );
        context.subscriptions.push(doctor);
        doctor.onDidDispose(() => {
          doctor = undefined;
        });
      }
      return doctor;
    }
    ["build-import", "build-connect", "sources-scan", "doctor-run"].forEach(
      command => {
        const cancel = commands.registerCommand("metals." + command, async () =>
          client.sendRequest(ExecuteCommandRequest.type, { command: command })
        );
        context.subscriptions.push(cancel);
      }
    );

    // Open or close the extension output channel. The user may have to trigger
    // this command twice in case the channel has been focused through another
    // button. There is no `isFocused` API to check if a channel is focused.
    var channelOpen = false;
    commands.registerCommand(Commands.TOGGLE_LOGS, () => {
      if (channelOpen) {
        client.outputChannel.hide();
        channelOpen = false;
      } else {
        client.outputChannel.show(true);
        channelOpen = true;
      }
    });

    commands.registerCommand(Commands.FOCUS_DIAGNOSTICS, () => {
      commands.executeCommand("workbench.action.problems.focus");
    });

    // Handle the metals/executeClientCommand extension notification.
    client.onNotification(ExecuteClientCommand.type, params => {
      if (params.command == "metals-run-doctor") {
        const html = params.arguments[0];
        if (typeof html === "string") {
          const panel = getDoctorPanel();
          panel.webview.html = html;
        }
      }
      // Ignore other commands since they are less important.
    });

    // The server updates the client with a brief text message about what
    // it is currently doing, for example "Compiling..".
    const item = window.createStatusBarItem(StatusBarAlignment.Right, 100);
    item.command = Commands.TOGGLE_LOGS;
    item.hide();
    client.onNotification(MetalsStatus.type, params => {
      item.text = params.text;
      if (params.show) {
        item.show();
      } else if (params.hide) {
        item.hide();
      }
      if (params.tooltip) {
        item.tooltip = params.tooltip;
      }
      if (params.command) {
        item.command = params.command;
        commands.getCommands().then(values => {
          if (values.indexOf(params.command) < 0) {
            commands.registerCommand(params.command, () => {
              client.sendRequest(ExecuteCommandRequest.type, {
                command: params.command
              });
            });
          }
        });
      } else {
        item.command = undefined;
      }
    });

    window.onDidChangeActiveTextEditor(editor => {
      if (editor.document.languageId == "scala") {
        client.sendNotification(
          MetalsDidFocus.type,
          editor.document.uri.toString()
        );
      }
    });

    // Long running tasks such as "import project" trigger start a progress
    // bar with a "cancel" button.
    client.onRequest(MetalsSlowTask.type, (params, requestToken) => {
      return new Promise(requestResolve => {
        window.withProgress(
          {
            location: ProgressLocation.Notification,
            title: params.message,
            cancellable: true
          },
          (progress, progressToken) => {
            // Open logs so user can keep track of progress.
            client.outputChannel.show(true);

            // Update total running time every second.
            var seconds = 0;
            const interval = setInterval(() => {
              seconds += 1;
              progress.report({ message: readableSeconds(seconds) });
            }, 1000);

            // Hide logs and clean up resources on completion.
            function onComplete() {
              clearInterval(interval);
              client.outputChannel.hide();
            }

            // Client triggered cancelation from the progress notification.
            progressToken.onCancellationRequested(() => {
              onComplete();
              requestResolve({ cancel: true });
            });

            return new Promise(progressResolve => {
              // Server completed long running task.
              requestToken.onCancellationRequested(() => {
                onComplete();
                progress.report({ increment: 100 });
                setTimeout(() => progressResolve(), 1000);
              });
            });
          }
        );
      });
    });
  });
}

function readableSeconds(totalSeconds: number): string {
  const minutes = (totalSeconds / 60) | 0;
  const seconds = totalSeconds % 60;
  if (minutes > 0) {
    if (seconds === 0) return `${minutes}m`;
    else return `${minutes}m${seconds}s`;
  } else {
    return `${seconds}s`;
  }
}

function enableScaladocIndentation() {
  // Adapted from:
  // https://github.com/Microsoft/vscode/blob/9d611d4dfd5a4a101b5201b8c9e21af97f06e7a7/extensions/typescript/src/typescriptMain.ts#L186
  languages.setLanguageConfiguration("scala", {
    indentationRules: {
      // ^(.*\*/)?\s*\}.*$
      decreaseIndentPattern: /^(.*\*\/)?\s*\}.*$/,
      // ^.*\{[^}"']*$
      increaseIndentPattern: /^.*\{[^}"']*$/
    },
    wordPattern: /(-?\d*\.\d\w*)|([^\`\~\!\@\#\%\^\&\*\(\)\-\=\+\[\{\]\}\\\|\;\:\'\"\,\.\<\>\/\?\s]+)/g,
    onEnterRules: [
      {
        // e.g. /** | */
        beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
        afterText: /^\s*\*\/$/,
        action: { indentAction: IndentAction.IndentOutdent, appendText: " * " }
      },
      {
        // e.g. /** ...|
        beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
        action: { indentAction: IndentAction.None, appendText: " * " }
      },
      {
        // e.g.  * ...|
        beforeText: /^(\t|(\ \ ))*\ \*(\ ([^\*]|\*(?!\/))*)?$/,
        action: { indentAction: IndentAction.None, appendText: "* " }
      },
      {
        // e.g.  */|
        beforeText: /^(\t|(\ \ ))*\ \*\/\s*$/,
        action: { indentAction: IndentAction.None, removeText: 1 }
      },
      {
        // e.g.  *-----*/|
        beforeText: /^(\t|(\ \ ))*\ \*[^/]*\*\/\s*$/,
        action: { indentAction: IndentAction.None, removeText: 1 }
      }
    ]
  });
}
