package tests.mcp

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.spec.McpSchema.InitializeResult

abstract class TestMcpBaseClient(implicit protected val ec: ExecutionContext) {

  protected val objectMapper: ObjectMapper = new ObjectMapper()

  protected def callTool(
      toolName: String,
      params: com.fasterxml.jackson.databind.node.ObjectNode,
  ): Future[List[String]]

  def initialize(): Future[InitializeResult]

  def shutdown(): Future[Unit]

  def findDep(
      organization: String,
      name: Option[String] = None,
      version: Option[String] = None,
  ): Future[List[String]] = {
    val params = objectMapper.createObjectNode()
    params.put("organization", organization)
    name.foreach(n => params.put("name", n))
    version.foreach(v => params.put("version", v))
    callTool("find-dep", params)
  }

  def compileFile(filePath: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fileInFocus", filePath)
    callTool("compile-file", params).map(_.mkString)
  }

  def compileModule(module: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("module", module)
    callTool("compile-module", params).map(_.mkString)
  }

  def compileFull(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("compile-full", params).map(_.mkString)
  }

  def listModules(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("list-modules", params).map(_.mkString)
  }

  def formatFile(filePath: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fileInFocus", filePath)
    callTool("format-file", params).map(_.mkString)
  }

  def generateScalafixRule(
      ruleImplementation: String,
      description: String,
      sampleCode: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("ruleImplementation", ruleImplementation)
    params.put("description", description)
    sampleCode.foreach(code => params.put("sampleCode", code))
    callTool("generate-scalafix-rule", params).map(_.mkString)
  }

  def listScalafixRules(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("list-scalafix-rules", params).map(_.mkString)
  }

  def runScalafixRule(
      ruleName: String,
      filePath: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("ruleName", ruleName)
    filePath.foreach(path => params.put("fileToRunOn", path))
    callTool("run-scalafix-rule", params).map(_.mkString)
  }
}
