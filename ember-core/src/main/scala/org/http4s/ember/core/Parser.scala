/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.core

import cats._
import cats.effect.{MonadThrow => _, _}
import cats.effect.kernel.Deferred
import cats.syntax.all._
import fs2._
import org.http4s._
import scala.annotation.switch
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

private[ember] object Parser {

  object HeaderP {

    def parseHeaders[F[_]: MonadThrow](
        s: Stream[F, Byte],
        maxHeaderLength: Int,
        acc: Option[ParseHeadersIncomplete])
        : Pull[F, Nothing, (Headers, Boolean, Option[Long], Stream[F, Byte])] =
      s.pull.uncons.flatMap {
        case Some((chunk, tl)) =>
          val nextArr: Array[Byte] = acc match {
            case None => chunk.toArray
            case Some(last) => combineArrays(last.bv, chunk.toArray)
          }
          val result = acc match {
            case None => headersInSection(nextArr)
            case Some(
                  ParseHeadersIncomplete(
                    _,
                    accHeaders,
                    idx,
                    state,
                    name,
                    start,
                    chunked,
                    contentLength)) =>
              headersInSection(nextArr, idx, state, accHeaders, chunked, contentLength, name, start)
          }
          result match {
            case ParseHeadersCompleted(headers, rest, chunked, length) =>
              Pull.pure((headers, chunked, length, Stream.chunk(Chunk.array(rest)) ++ tl))
            case p @ ParseHeadersError(_) => Pull.raiseError[F](p)
            case p @ ParseHeadersIncomplete(_, _, _, _, _, _, _, _) =>
              if (nextArr.size <= maxHeaderLength) parseHeaders(tl, maxHeaderLength, p.some)
              else
                Pull.raiseError[F](
                  ParseHeadersError(
                    new Throwable(
                      s"Parse Headers Exceeded Max Content-Length current size: ${nextArr.size}, only allow ${maxHeaderLength}")
                  )
                )
          }
        case None =>
          Pull.raiseError[F](
            ParseHeadersError(new Throwable("Reached Ended of Stream Looking for Headers")))
      }

    private val colon: Byte = ':'.toByte
    private val cr: Byte = '\r'.toByte
    private val lf: Byte = '\n'.toByte
    private val space: Byte = ' '.toByte
    private val contentLengthS = "Content-Length"
    private val transferEncodingS = "Transfer-Encoding"
    private val chunkedS = "chunked"

    sealed trait ParseHeaderResult
    final case class ParseHeadersError(cause: Throwable)
        extends Throwable(
          s"Encountered Error Attempting to Parse Headers - ${cause.getMessage}",
          cause)
        with ParseHeaderResult
    final case class ParseHeadersCompleted(
        headers: Headers,
        rest: Array[Byte],
        chunked: Boolean,
        length: Option[Long])
        extends ParseHeaderResult
    final case class ParseHeadersIncomplete(
        bv: Array[Byte],
        accHeaders: List[Header],
        idx: Int,
        state: Byte,
        name: Option[String],
        start: Int,
        chunked: Boolean,
        contentLength: Option[Long])
        extends ParseHeaderResult

    def headersInSection(
        bv: Array[Byte],
        initIndex: Int = 0,
        initState: Byte = 0, //HeaderNameOrPostCRLF,
        initHeaders: List[Header] = List.empty,
        initChunked: Boolean = false,
        initContentLength: Option[Long] = None,
        initName: Option[String] = None,
        initStart: Int = 0
    ): ParseHeaderResult = {
      import scala.collection.mutable.ListBuffer
      var idx = initIndex
      var state = initState
      var throwable: Throwable = null
      var complete = false
      var chunked: Boolean = initChunked
      var contentLength: Option[Long] = initContentLength

      val headers = ListBuffer(initHeaders: _*)
      var name: String = initName.orNull
      var start = initStart

      while (!complete && idx < bv.size) {
        (state: @switch) match {
          case 0 => // HeaderNameOrPostCRLF
            val current = bv(idx)
            // if current index is colon our name is complete
            if (current == colon) {
              state = 1 // set state to check for header value
              name = new String(bv, start, idx - start) // extract name string
              start = idx + 1 // advance past colon for next start
              if ((bv.size >= idx + 1) && (bv(idx + 1) == space)) {
                start += 1 // if colon is followed by space advance again
                idx += 1 // double advance index here to skip the space
              }
              // double CRLF condition - Termination of headers
            } else if (current == cr && (idx + 1 < bv.size) && (bv(idx + 1) == lf)) {
              idx += 1 // double advance to drop cr AND lf
              complete = true // completed terminate loop
            }
          case 1 => // HeaderValue
            val current = bv(idx)
            // If crlf is next we have completed the header value
            if (current == cr && ((bv.size >= idx + 1) && bv(idx + 1) == lf)) {
              val hValue = new String(bv, start, idx - start) // extract header value

              val hName = name // copy var to val
              name = null // set name back to null
              val newHeader = Header(hName, hValue) // create header
              if (hName.equalsIgnoreCase(contentLengthS)) // Check if this is content-length.
                try contentLength = hValue.toLong.some
                catch {
                  case scala.util.control.NonFatal(e) =>
                    throwable = e
                    complete = true
                }

              if (hName.equalsIgnoreCase(transferEncodingS)) // Check if this is Transfer-encoding
                chunked = hValue.contains(chunkedS)
              start = idx + 2 // Next Start is after the CRLF
              idx += 1 // Double advance to skip CRLF
              headers += newHeader // Add Header
              state = 0 // Go back to Looking for HeaderName or Termination
            }
        }
        idx += 1 // Single Advance Every Iteration
      }

      if (throwable != null) ParseHeadersError(throwable)
      else if (complete)
        ParseHeadersCompleted(Headers(headers.toList), bv.drop(idx), chunked, contentLength)
      else
        ParseHeadersIncomplete(
          bv,
          headers.toList,
          idx,
          state,
          Option(name),
          start,
          chunked,
          contentLength)
    }
  }

  object Request {

    object ReqPrelude {

      val emptyStreamError = ParsePreludeError("Cannot Parse Empty Stream", None, None, None, None)

      def parsePrelude[F[_]: MonadThrow](
          s: Stream[F, Byte],
          maxHeaderLength: Int,
          acc: Option[ParsePreludeIncomlete] = None)
          : Pull[F, Nothing, (Method, Uri, HttpVersion, Stream[F, Byte])] =
        s.pull.uncons.flatMap {
          case Some((chunk, tl)) =>
            val next: Array[Byte] = acc match {
              case None => chunk.toArray
              case Some(ic) => combineArrays(ic.bv, chunk.toArray)
            }
            ReqPrelude.preludeInSection(next) match {
              case ParsePreludeComplete(m, u, h, rest) =>
                val out = if (rest.isEmpty) tl else Stream.chunk(Chunk.array(rest)) ++ tl
                Pull.pure((m, u, h, out))
              case t @ ParsePreludeError(_, _, _, _, _) => Pull.raiseError[F](t)
              case p @ ParsePreludeIncomlete(_, _, method, uri, httpVersion) =>
                if (next.size <= maxHeaderLength)
                  parsePrelude(tl, maxHeaderLength, p.some)
                else
                  Pull.raiseError[F](
                    ParsePreludeError(
                      "Reached Max Header Length Looking for Request Prelude",
                      None,
                      method,
                      uri,
                      httpVersion))
            }
          case None =>
            acc match {
              case None => Pull.raiseError(emptyStreamError)
              case Some(incomplete) if incomplete.bv.isEmpty => Pull.raiseError(emptyStreamError)
              case Some(incomplete) =>
                Pull.raiseError(
                  ParsePreludeError(
                    s"Unexpected EOF - $incomplete",
                    None,
                    incomplete.method,
                    incomplete.uri,
                    incomplete.httpVersion))
            }
        }

      // sealed trait ParsePreludeState
      // 0 case object GetMethod extends ParsePreludeState
      // 1 case object GetUri extends ParsePreludeState
      // 2 case object GetHttpVersion extends ParsePreludeState
      private val space = ' '.toByte
      private val cr: Byte = '\r'.toByte
      private val lf: Byte = '\n'.toByte

      sealed trait ParsePreludeResult
      case class ParsePreludeError(
          message: String,
          caused: Option[Throwable],
          method: Option[Method],
          uri: Option[Uri],
          httpVersion: Option[HttpVersion]
      ) extends Exception(
            s"Parse Prelude Error Encountered - Message: $message - Partially Decoded: $method $uri $httpVersion",
            caused.orNull
          )
          with ParsePreludeResult
      final case class ParsePreludeIncomlete(
          idx: Int,
          bv: Array[Byte],
          // buffer: String,
          method: Option[Method],
          uri: Option[Uri],
          httpVersion: Option[HttpVersion]
      ) extends ParsePreludeResult
      final case class ParsePreludeComplete(
          method: Method,
          uri: Uri,
          httpVersion: HttpVersion,
          rest: Array[Byte]
      ) extends ParsePreludeResult
      // Method SP URI SP HttpVersion CRLF - REST
      def preludeInSection(
          bv: Array[Byte],
          initIdx: Int = 0,
          initMethod: Option[Method] = None,
          initUri: Option[Uri] = None,
          initHttpVersion: Option[HttpVersion] = None
      ): ParsePreludeResult = {
        var idx = initIdx
        var state: Byte = 0
        var complete = false

        var throwable: Throwable = null
        var method: Method = initMethod.orNull
        var uri: Uri = initUri.orNull
        var httpVersion: HttpVersion = initHttpVersion.orNull

        var start = 0
        while (!complete && idx < bv.size) {
          val value = bv(idx)
          (state: @switch) match {
            case 0 =>
              if (value == space) {
                Method.fromString(new String(bv, start, idx - start)) match {
                  case Left(e) =>
                    throwable = e
                    complete = true
                  case Right(m) =>
                    method = m
                }
                start = idx + 1
                state = 1
              }
            case 1 =>
              if (value == space) {
                Uri.fromString(new String(bv, start, idx - start)) match {
                  case Left(e) =>
                    throwable = e
                    complete = true
                  case Right(u) =>
                    uri = u
                }
                start = idx + 1
                state = 2
              }
            case 2 =>
              if (value == cr && ((bv.size >= idx + 1) && bv(idx + 1) == lf)) {
                HttpVersion.fromString(new String(bv, start, idx - start)) match {
                  case Left(e) =>
                    throwable = e
                    complete = true
                  case Right(h) =>
                    httpVersion = h
                }
                complete = true
                idx += 1 // Double Advance
              }
          }
          idx += 1
        }

        if (throwable != null)
          ParsePreludeError(
            throwable.getMessage(),
            Option(throwable),
            Option(method),
            Option(uri),
            Option(httpVersion)
          )
        else if (method != null && uri != null && httpVersion != null)
          ParsePreludeComplete(method, uri, httpVersion, bv.drop(idx))
        else
          ParsePreludeIncomlete(idx, bv, Option(method), Option(uri), Option(httpVersion))
      }
    }

    def parser[F[_]: Concurrent: Temporal](maxHeaderLength: Int, timeout: Option[FiniteDuration])(
        s: Stream[F, Byte]): F[(Request[F], Stream[F, Byte])] =
      Deferred[F, Headers].flatMap { trailers =>
        val baseStream = ReqPrelude
          .parsePrelude[F](s, maxHeaderLength, None)
          .flatMap { case (method, uri, httpVersion, rest) =>
            HeaderP.parseHeaders(rest, maxHeaderLength, None).flatMap {
              case (headers, chunked, contentLength, rest) =>
                val baseReq: org.http4s.Request[F] = org.http4s.Request[F](
                  method = method,
                  uri = uri,
                  httpVersion = httpVersion,
                  headers = headers
                )
                val req: org.http4s.Request[F] =
                  if (chunked)
                    baseReq
                      .withAttribute(Message.Keys.TrailerHeaders[F], trailers.get)
                      .withBodyStream(
                        rest.through(ChunkedEncoding.decode(maxHeaderLength, trailers)))
                  else
                    baseReq.withBodyStream(rest.take(contentLength.getOrElse(0L)))

                Pull.output1((req, rest))
            }
          }
          .stream
          .take(1)

        val action = baseStream.compile.lastOrError
        timeout match {
          case None => action
          case Some(timeout) => Temporal[F].timeout(action, timeout)
        }
      }
  }

  object Response {
    def parser[F[_]: Concurrent: Temporal](maxHeaderLength: Int, timeout: Option[FiniteDuration])(
        s: Stream[F, Byte]): F[(Response[F], Stream[F, Byte])] =
      Deferred[F, Headers].flatMap { trailers =>
        val base = RespPrelude
          .parsePrelude(s, maxHeaderLength, None)
          .flatMap { case (httpVersion, status, s) =>
            HeaderP.parseHeaders(s, maxHeaderLength, None).flatMap {
              case (headers, chunked, contentLength, rest) =>
                val baseResp = org.http4s.Response[F](
                  httpVersion = httpVersion,
                  status = status,
                  headers = headers
                )
                val resp: org.http4s.Response[F] =
                  if (chunked)
                    baseResp
                      .withAttribute(Message.Keys.TrailerHeaders[F], trailers.get)
                      .withBodyStream(
                        rest.through(ChunkedEncoding.decode(maxHeaderLength, trailers)))
                  else
                    baseResp.withBodyStream(rest.take(contentLength.getOrElse(0L)))
                Pull.output1((resp, rest))
            }
          }
          .stream
          .take(1)

        val action = base.compile.lastOrError
        timeout.fold(action)(duration => Temporal[F].timeout(action, duration))
      }

    object RespPrelude {

      val emptyStreamError = RespPreludeError("Cannot Parse Empty Stream", None)

      def parsePrelude[F[_]: MonadThrow](
          s: Stream[F, Byte],
          maxHeaderLength: Int,
          acc: Option[Array[Byte]] = None)
          : Pull[F, Nothing, (HttpVersion, Status, Stream[F, Byte])] =
        s.pull.uncons.flatMap {
          case Some((chunk, tl)) =>
            val next: Array[Byte] = acc match {
              case None => chunk.toArray
              case Some(remains) => combineArrays(remains, chunk.toArray)
            }
            preludeInSection(next) match {
              case RespPreludeComplete(httpVersion, status, rest) =>
                Pull.pure((httpVersion, status, Stream.chunk(Chunk.array(rest)) ++ tl))
              case t @ RespPreludeError(_, _) => Pull.raiseError[F](t)
              case RespPreludeIncomplete =>
                if (next.size <= maxHeaderLength)
                  parsePrelude(tl, maxHeaderLength, next.some)
                else
                  Pull.raiseError[F](
                    RespPreludeError(
                      "Reached Max Header Length Looking for Response Prelude",
                      None))
            }
          case None =>
            acc match {
              case None => Pull.raiseError(emptyStreamError)
              case Some(incomplete) if incomplete.isEmpty => Pull.raiseError(emptyStreamError)
              case Some(_) =>
                Pull.raiseError[F](
                  RespPreludeError(
                    "Unexpectedly Reached Ended of Stream Looking for Response Prelude",
                    None)
                )
            }

        }

      private val space = ' '.toByte
      private val cr: Byte = '\r'.toByte
      private val lf: Byte = '\n'.toByte

      sealed trait RespPreludeResult
      case class RespPreludeComplete(httpVersion: HttpVersion, status: Status, rest: Array[Byte])
          extends RespPreludeResult
      case object RespPreludeIncomplete extends RespPreludeResult
      case class RespPreludeError(message: String, cause: Option[Throwable])
          extends Throwable(
            s"Received Error while parsing prelude - Message: $message - ${cause.map(_.getMessage)}",
            cause.orNull)
          with RespPreludeResult

      // HTTP/1.1 200 OK
      // Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
      def preludeInSection(bv: Array[Byte]): RespPreludeResult = {
        var complete = false
        var idx = 0
        var throwable: Throwable = null
        var httpVersion: HttpVersion = null

        var codeS: String = null
        // val reason: String = null
        var status: Status = null
        var start = 0
        var state = 0 // 0 Is for HttpVersion, 1 for Status Code, 2 For Reason Phrase

        while (!complete && idx < bv.size) {
          val value = bv(idx)
          (state: @switch) match {
            case 0 =>
              if (value == space) {
                val s = new String(bv, start, idx - start)
                HttpVersion.fromString(s) match {
                  case Left(e) =>
                    throwable = e
                    complete = true
                  case Right(h) =>
                    httpVersion = h
                }
                start = idx + 1
                state = 1
              }
            case 1 =>
              if (value == space) {
                codeS = new String(bv, start, idx - start)
                state = 2
                start = idx + 1
              }
            case 2 =>
              if (value == cr && ((bv.size >= idx + 1) && bv(idx + 1) == lf)) {
                val reason = new String(bv, start, idx - start)
                try {
                  val codeInt = codeS.toInt
                  Status.fromIntAndReason(codeInt, reason) match {
                    case Left(e) =>
                      throw e
                    case Right(s) =>
                      status = s
                      complete = true
                      idx += 1 // Double Advance
                  }
                } catch {
                  case scala.util.control.NonFatal(e) =>
                    throwable = e
                    complete = true
                }
              }
          }
          idx += 1
        }

        if (throwable != null) RespPreludeError("Encounterd Error parsing", Option(throwable))
        if (httpVersion != null && status != null)
          RespPreludeComplete(httpVersion, status, bv.drop(idx))
        else RespPreludeIncomplete
      }
    }
  }

  private def combineArrays[A: scala.reflect.ClassTag](a1: Array[A], a2: Array[A]): Array[A] = {
    val buff = mutable.ArrayBuffer[A]()
    buff.++=(a1)
    buff.++=(a2)
    buff.toArray
  }
}
