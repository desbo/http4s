package org.http4s
package server

import syntax.ServiceOps

class HttpServiceSpec extends Http4sSpec {

 val srvc1 = HttpService {
   case req if req.pathInfo == "/match" =>
    Response(Status.Ok).withBody("match")

   case req if req.pathInfo == "/conflict" =>
     Response(Status.Ok).withBody("srvc1conflict")

   case req if req.pathInfo == "/notfound" =>
     Response(Status.NotFound).withBody("notfound")
  }

  val srvc2 = HttpService {
    case req if req.pathInfo == "/srvc2" =>
      Response(Status.Ok).withBody("srvc2")

    case req if req.pathInfo == "/conflict" =>
     Response(Status.Ok).withBody("srvc2conflict")
  }

  val aggregate1 = srvc1 orElse srvc2

  "HttpService" should {
    "Return a valid Response from the first service of an aggregate" in {
      aggregate1.orNotFound(Request(uri = uri("/match"))).as[String].unsafePerformSync must_== ("match")
    }

    "Return a custom NotFound from the first service of an aggregate" in {
      aggregate1.orNotFound(Request(uri = uri("/notfound"))).as[String].unsafePerformSync must_== ("notfound")
    }

    "Accept the first matching route in the case of overlapping paths" in {
      aggregate1.orNotFound(Request(uri = uri("/conflict"))).as[String].unsafePerformSync must_== ("srvc1conflict")
    }

    "Fall through the first service that doesn't match to a second matching service" in {
      aggregate1.orNotFound(Request(uri = uri("/srvc2"))).as[String].unsafePerformSync must_== ("srvc2")
    }

    "Properly fall through two aggregated service if no path matches" in {
      aggregate1.apply(Request(uri = uri("/wontMatch"))).unsafePerformSync must be (Pass)
    }
  }
}
