//> using scala 3.6
//> using toolkit default

// Cherry-pick all commits reachable from <to-tag> but not from <from-tag>
// into the currently checked-out branch of the target repository.
//
// Usage:
//   scala-cli run scripts/cherryPickBetweenTags.scala -- <from-tag> <to-tag> [repo-path]
//
// Notes:
//   - <from-tag> is exclusive, <to-tag> is inclusive (standard `git log A..B` semantics).
//   - Commits are applied in chronological order (oldest first) via `git cherry-pick -x`.
//   - When a conflict is encountered the script pauses and lets the user fix it
//     manually in another terminal, then resumes / skips / aborts.
//   - The script never modifies the target repository's HEAD before cherry-picking,
//     so make sure the desired target branch is checked out first.

import scala.util.control.NonFatal

@main def cherryPickBetweenTags(args: String*): Unit =
  val (fromTag, toTag, repoPath) = args.toList match
    case from :: to :: Nil          => (from, to, ".")
    case from :: to :: path :: Nil  => (from, to, path)
    case _ =>
      System.err.println(
        "Usage: cherryPickBetweenTags <from-tag> <to-tag> [repo-path]"
      )
      sys.exit(2)

  val repo = os.Path(repoPath, os.pwd)
  if !os.exists(repo / ".git") then
    System.err.println(s"Not a git repository: $repo")
    sys.exit(2)

  verifyRef(repo, fromTag)
  verifyRef(repo, toTag)

  val currentBranch = gitOut(repo, "rev-parse", "--abbrev-ref", "HEAD").trim
  println(s"Repository: $repo")
  println(s"Current branch: $currentBranch")
  println(s"Range: $fromTag..$toTag")

  val commits = listCommits(repo, fromTag, toTag)
  if commits.isEmpty then
    println("Nothing to cherry-pick. The target is already up to date.")
    return

  println(s"About to cherry-pick ${commits.size} commit(s):")
  commits.foreach: c =>
    println(s"  ${c.shortSha}  ${c.subject}")
  if !askYesNo("Continue?") then
    println("Aborted by user.")
    sys.exit(1)

  val total = commits.size
  commits.zipWithIndex.foreach: (commit, idx) =>
    println(s"\n[${idx + 1}/$total] Cherry-picking ${commit.shortSha} ${commit.subject}")
    applyCherryPick(repo, commit)

  println("\nAll done. Remember to review the result and push when ready.")
end cherryPickBetweenTags

private case class CommitInfo(sha: String, subject: String):
  def shortSha: String = sha.take(10)

private def verifyRef(repo: os.Path, ref: String): Unit =
  val res = os.proc("git", "rev-parse", "--verify", s"$ref^{commit}")
    .call(cwd = repo, check = false, stderr = os.Pipe)
  if res.exitCode != 0 then
    System.err.println(s"Cannot resolve ref '$ref' in $repo:\n${res.err.text()}")
    sys.exit(2)

private def listCommits(repo: os.Path, fromTag: String, toTag: String): List[CommitInfo] =
  val raw = gitOut(
    repo,
    "log",
    "--no-merges",
    "--reverse",
    "--pretty=format:%H%x09%s",
    s"$fromTag..$toTag"
  )
  raw.linesIterator
    .filter(_.nonEmpty)
    .map: line =>
      line.split('\t') match
        case Array(sha, subject) => CommitInfo(sha, subject)
        case Array(sha)          => CommitInfo(sha, "")
        case other               => CommitInfo(other.head, other.drop(1).mkString("\t"))
    .toList

private def applyCherryPick(repo: os.Path, commit: CommitInfo): Unit =
  val res = os.proc("git", "cherry-pick", "-x", commit.sha)
    .call(cwd = repo, check = false, stdout = os.Inherit, stderr = os.Inherit)
  if res.exitCode == 0 then
    println(s"  ✅  applied cleanly")
  else
    handleConflict(repo, commit)

private def handleConflict(repo: os.Path, commit: CommitInfo): Unit =
  println(s"  ❌  conflict while cherry-picking ${commit.shortSha}")
  showConflicts(repo)
  while true do
    println(
      """
        |Resolve the conflicts manually in another terminal
        |(edit files, then `git add` the resolved ones), then choose:
        |  [c] continue cherry-pick after resolving conflicts
        |  [s] skip this commit (git cherry-pick --skip)
        |  [r] show conflict status again
        |  [a] abort cherry-pick and exit""".stripMargin
    )
    print("> ")
    io.StdIn.readLine() match
      case null =>
        abortCherryPick(repo)
        sys.exit(1)
      case line =>
        line.trim.toLowerCase match
          case "c" =>
            if continueCherryPick(repo) then return
            else
              println("  ⚠️  still conflicting; resolve remaining paths and try again.")
              showConflicts(repo)
          case "s" =>
            skipCherryPick(repo)
            println(s"  ⏭️  skipped ${commit.shortSha}")
            return
          case "r" =>
            showConflicts(repo)
          case "a" =>
            abortCherryPick(repo)
            println("Cherry-pick aborted by user.")
            sys.exit(1)
          case ""  => ()
          case _   => println("Unrecognised choice.")
  end while

private def continueCherryPick(repo: os.Path): Boolean =
  if hasUnresolvedConflicts(repo) then
    println("  Still unresolved paths detected:")
    showConflicts(repo)
    return false
  val env = Map("GIT_EDITOR" -> "true")
  val res = os.proc("git", "-c", "core.editor=true", "cherry-pick", "--continue")
    .call(cwd = repo, check = false, env = env, stdout = os.Inherit, stderr = os.Inherit)
  res.exitCode == 0

private def skipCherryPick(repo: os.Path): Unit =
  os.proc("git", "cherry-pick", "--skip")
    .call(cwd = repo, stdout = os.Inherit, stderr = os.Inherit)

private def abortCherryPick(repo: os.Path): Unit =
  try
    os.proc("git", "cherry-pick", "--abort")
      .call(cwd = repo, check = false, stdout = os.Inherit, stderr = os.Inherit)
  catch case NonFatal(_) => ()

private def hasUnresolvedConflicts(repo: os.Path): Boolean =
  val res = gitOut(repo, "diff", "--name-only", "--diff-filter=U")
  res.linesIterator.exists(_.nonEmpty)

private def showConflicts(repo: os.Path): Unit =
  val unresolved = gitOut(repo, "diff", "--name-only", "--diff-filter=U")
    .linesIterator
    .filter(_.nonEmpty)
    .toList
  if unresolved.isEmpty then
    println("  No unresolved paths according to git (you may still need to `git add` them).")
  else
    println("  Unresolved paths:")
    unresolved.foreach(p => println(s"    - $p"))

private def gitOut(repo: os.Path, args: String*): String =
  os.proc("git" +: args)
    .call(cwd = repo)
    .out
    .text()

private def askYesNo(msg: String): Boolean =
  print(s"$msg [y/n] > ")
  io.StdIn.readLine() match
    case null              => false
    case s if s.nonEmpty   =>
      s.trim.toLowerCase match
        case "y" | "yes" => true
        case "n" | "no"  => false
        case _           => askYesNo(msg)
    case _ => askYesNo(msg)
