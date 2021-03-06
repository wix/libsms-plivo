package com.wix.sms.plivo.testkit

import java.util.concurrent.atomic.AtomicReference

import akka.http.scaladsl.model._
import com.google.api.client.util.Base64
import com.wix.e2e.http.RequestHandler
import com.wix.e2e.http.server.WebServerFactory.aMockWebServerWith
import com.wix.e2e.http.client.extractors.HttpMessageExtractors._
import com.wix.sms.model.Sender
import com.wix.sms.plivo.model.{SendMessageRequestParser, SendMessageResponse, SendMessageResponseParser}
import com.wix.sms.plivo.{Credentials, PlivoHelper}

class PlivoDriver(port: Int) {
  private val delegatingHandler: RequestHandler = { case r: HttpRequest => handler.get().apply(r) }
  private val notFoundHandler: RequestHandler = { case _: HttpRequest => HttpResponse(status = StatusCodes.NotFound) }

  private val handler = new AtomicReference(notFoundHandler)

  private val probe = aMockWebServerWith(delegatingHandler).onPort(port).build

  def start() {
    probe.start()
  }

  def stop() {
    probe.stop()
  }

  def reset() {
    handler.set(notFoundHandler)
  }

  def aSendMessageFor(credentials: Credentials, sender: Sender, destPhone: String, text: String): SendMessageCtx = {
    new SendMessageCtx(
      credentials = credentials,
      sender = sender,
      destPhone = destPhone,
      text = text)
  }

  private def prependHandler(handle: RequestHandler) =
    handler.set(handle orElse handler.get())

  class SendMessageCtx(credentials: Credentials, sender: Sender, destPhone: String, text: String) {
    private val expectedRequest = PlivoHelper.createSendMessageRequest(
      sender = sender,
      destPhone = destPhone,
      text = text
    )

    def returns(msgId: String): Unit = {
      val response = SendMessageResponse(
        api_id = "some api id",
        message_uuid = Some(Seq(msgId))
      )

      val responseJson = SendMessageResponseParser.stringify(response)
      returnsJson(StatusCodes.OK, responseJson)
    }

    def failsWith(error: String): Unit = {
      val response = SendMessageResponse(
        api_id = "some api id",
        error = Some(error)
      )

      val responseJson = SendMessageResponseParser.stringify(response)
      returnsJson(StatusCodes.OK, responseJson)
    }

    def failsOnLandLineDestination(): Unit = {
      val response = SendMessageResponse(
        api_id = "some api id",
        error = Some("No rate/prefix matching dst number")
      )

      val responseJson = SendMessageResponseParser.stringify(response)
      returnsJson(StatusCodes.BadRequest, responseJson)
    }

    private def returnsJson(statusCode: StatusCode, responseJson: String): Unit = {
      val path = s"/Account/${credentials.authId}/Message/"
      prependHandler({
        case HttpRequest(
        HttpMethods.POST,
        Uri.Path(`path`),
        headers,
        entity,
        _) if isStubbedRequestEntity(entity) && isStubbedHeaders(headers) =>
          HttpResponse(
            status = statusCode,
            entity = HttpEntity(ContentTypes.`application/json`, responseJson))
      })
    }

    private def isStubbedRequestEntity(entity: HttpEntity): Boolean = {
      val requestJson = entity.extractAsString
      val request = SendMessageRequestParser.parse(requestJson)

      request == expectedRequest
    }

    private def isStubbedHeaders(headers: Seq[HttpHeader]): Boolean = {
      val expectedAuthorizationValue = s"Basic ${Base64.encodeBase64String(s"${credentials.authId}:${credentials.authToken}".getBytes("UTF-8"))}"

      headers.exists { header =>
        header.name == "Authorization" &&
          header.value == expectedAuthorizationValue
      }
    }
  }
}
