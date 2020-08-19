import $ivy.`org.kohsuke:github-api:1.114`

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.kohsuke.github.GitHubBuilder

val defaultToken = sys.env.get("GITHUB_TOKEN")

@main
def main(
    firstTag: String,
    lastTag: String,
    githubToken: Seq[String] = defaultToken.toSeq
) = {

  val command = List(
    "git",
    "log",
    s"$firstTag..$lastTag",
    "--first-parent",
    "main",
    "--pretty=format:%H"
  )

  val token = githubToken.headOption.getOrElse {
    throw new Exception("No github API token was specified")
  }

  val output = os.proc(command).call().out.trim()

  val gh = new GitHubBuilder()
    .withOAuthToken(token)
    .build()

  val foundPRs = mutable.Set.empty[Int]
  for {
    // group in order to optimize API
    searchSha <-
      output.split('\n').grouped(5).map(_.mkString("SHA ", " SHA ", ""))
    allMatching =
      gh.searchIssues().q(s"repo:scalameta/metals type:pr $searchSha").list()
    pr <- allMatching.toList().asScala.sortBy(_.getClosedAt()).reverse
    prNumber = pr.getNumber()
    if !foundPRs(prNumber)
  } {
    foundPRs += prNumber
    val login = pr.getUser().getLogin()
    val formattedPR =
      s"""|- ${pr.getTitle()}
          |  [\\#${pr.getNumber()}](${pr.getHtmlUrl()})
          |  ([$login](https://github.com/$login))""".stripMargin
    println(formattedPR)
  }
}
