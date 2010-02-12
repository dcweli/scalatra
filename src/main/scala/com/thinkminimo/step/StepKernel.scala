package com.thinkminimo.step

import javax.servlet._
import javax.servlet.http._
import scala.util.DynamicVariable
import scala.util.matching.Regex
import scala.collection.mutable.HashSet
import scala.collection.JavaConversions._
import scala.xml.NodeSeq
import Session._

object StepKernel
{
  type Params = Map[String, String]
  type Action = () => Any

  val protocols = List("GET", "POST", "PUT", "DELETE")
}
import StepKernel._

trait StepKernel
{
  val Routes      = Map(protocols map (_ -> new HashSet[Route]): _*)
  val paramsMap   = new DynamicVariable[Params](null)

  def contentType = response.getContentType
  def contentType_=(value: String): Unit = response.setContentType(value)

  val defaultCharacterEncoding = "UTF-8"
  val _session    = new DynamicVariable[Session](null)
  val _response   = new DynamicVariable[HttpServletResponse](null)
  val _request    = new DynamicVariable[StepRequest](null)

  implicit def requestToStepRequest(r: HttpServletRequest) = StepRequest(r)

  class Route(val path: String, val action: Action) {
    val pattern = """:\w+"""
    val names = new Regex(pattern) findAllIn path toList
    val re = new Regex("^%s$" format path.replaceAll(pattern, "(.*?)"))

    def apply(realPath: String): Option[Params] =
      re findFirstMatchIn realPath map (x => Map(names zip x.subgroups : _*))

    override def toString() = path
  }

  def handle(request: HttpServletRequest, response: HttpServletResponse) {
    // As default, the servlet tries to decode params with ISO_8859-1.
    // It causes an EOFException if params are actually encoded with the other code (such as UTF-8)
    request.setCharacterEncoding(defaultCharacterEncoding)

    val realParams = request.getParameterMap.asInstanceOf[java.util.Map[String,Array[String]]]
      .map { case (k,v) => (k, v(0)) }.toMap

    def isMatchingRoute(route: Route) = {
      def exec(args: Params) = {
        paramsMap.withValue(args ++ realParams withDefaultValue(null)) {
          renderResponse(route.action())
        }
      }
      route(requestPath) map exec isDefined
    }

    response.setCharacterEncoding(defaultCharacterEncoding)

    _request.withValue(request) {
      _response.withValue(response) {
        _session.withValue(request) {
          paramsMap.withValue(realParams withDefaultValue(null)) {
            doBefore()
            if (Routes(request.getMethod) find isMatchingRoute isEmpty)
              renderResponse(doNotFound())
          }
        }
      }
    }
  }

  protected def requestPath: String

  private var doBefore: () => Unit = { () => () }
  def before(fun: => Any) = doBefore = { () => fun; () }

  protected var doNotFound: Action
  def notFound(fun: => Any) = doNotFound = { () => fun }

  def renderResponse(actionResult: Any) {
    if (contentType == null)
      contentType = inferContentType(actionResult)
    renderResponseBody(actionResult)
  }

  def inferContentType(actionResult: Any): String = actionResult match {
    case _: NodeSeq => "text/html"
    case _: Array[Byte] => "application/octet-stream"
    case _ => "text/plain"
  }

  def renderResponseBody(actionResult: Any) {
    actionResult match {
      case bytes: Array[Byte] =>
        response.getOutputStream.write(bytes)
      case _: Unit =>
        // If an action returns Unit, it assumes responsibility for the response
      case x: Any  =>
        response.getWriter.print(x.toString)
	  }
  }

  def params = paramsMap value
  def redirect(uri: String) = (_response value) sendRedirect uri
  def request = _request value
  def response = _response value
  def session = _session value
  def status(code: Int) = (_response value) setStatus code

  val List(get, post, put, delete) = protocols map routeSetter

  // functional programming means never having to repeat yourself
  private def routeSetter(protocol: String): (String) => (=> Any) => Unit = {
    def g(path: String, fun: => Any) { Routes(protocol) += new Route(path, () => fun) }
    (g _).curried
  }
}