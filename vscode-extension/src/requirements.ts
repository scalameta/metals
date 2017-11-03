const pathExists = require('path-exists');
const findJavaHome = require('find-java-home');
const expandHomeDir = require('expand-home-dir');

// Copy-pasted from https://github.com/dragos/dragos-vscode-scala/blob/master/scala/src/extension.ts
export class Requirements {

  public async getJavaHome(): Promise<string> {
    return await this.checkJavaRuntime();
  }

  private checkJavaRuntime(): Promise<any> {
    return new Promise((resolve, reject) => {
      let source: string;
      let javaHome: string = process.env['JDK_HOME'];
      if (javaHome) {
        source = 'The JDK_HOME environment variable';
      } else {
        javaHome = process.env['JAVA_HOME'];
        source = 'The JAVA_HOME environment variable';
      }

      if (javaHome) {
        javaHome = expandHomeDir(javaHome);
        if (!pathExists.sync(javaHome)) {
          reject(source + ' points to a missing folder');
        }
        return resolve(javaHome);
      }

      // No settings, let's try to detect as last resort.
      findJavaHome(function (err, home) {
        if (err) {
          reject('Java runtime could not be located');
        } else {
          resolve(home);
        }
      });
    });
  }
};
