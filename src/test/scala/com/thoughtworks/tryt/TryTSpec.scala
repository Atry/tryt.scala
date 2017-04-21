package com.thoughtworks.tryt
import org.scalatest._

import scalaz.std.AllInstances._
import scalaz.{-\/, @@, Applicative, BindRec, Functor, MonadError, \/, \/-}
import scala.concurrent.Promise
import scalaz.concurrent.Future._
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.concurrent.Future
import scala.util.control.NonFatal
import scalaz.Tags.Parallel
import com.thoughtworks.tryt.TryT._

class TryTSpec extends AsyncFreeSpec with Matchers with Inside {

  final case class Boom() extends Throwable

  final case class AnotherBoom() extends Throwable

  "Given a TryT transformed Future of Int" - {
    val futureTryInt: Future[Try[Int]] = Future.now(Try(3))
    val parallelFutureInt: TryT[Future, Int] = TryT(futureTryInt)
    "When map it to another Int" - {

      "And the mapping function works fine" - {
        val result: TryT[Future, Int] = Functor[TryT[Future, ?]].map[Int, Int](parallelFutureInt) { int =>
          int * int
        }
        val unwrap: Future[Try[Int]] = TryT.unwrap(result)
        "Then the result should be as expected" in {
          val p = Promise[Assertion]

          unwrap.unsafePerformAsync { tryInt =>
            inside(tryInt) {
              case Success(value) =>
                p.success {
                  value should be(9)
                }
            }
          }
          p.future
        }
      }

      "And the mapping function throws an exception" - {
        val result: TryT[Future, Int] = Functor[TryT[Future, ?]].map[Int, Int](parallelFutureInt) { int =>
          throw Boom()
          int * int
        }

        val unwrap: Future[Try[Int]] = TryT.unwrap(result)
        "Then the exception should be found in a Failure in the result Future" in {

          val p = Promise[Assertion]

          unwrap.unsafePerformAsync { tryInt =>
            inside(tryInt) {
              case Failure(e) =>
                p.success {
                  e should be(a[Boom])
                }
            }
          }
          p.future
        }
      }
    }
  }

  "Given a TryT transformed Future of Int" - {

    "When point it to a TryT transformed Future of Int" - {
      "And the point works fine" - {}
    }
  }

  "TryTMonadError point without exception" in {
    val tryTFutureInt: TryT[Future, Int] =
      MonadError[TryT[Future, ?], Throwable].point(1)

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Success(value) =>
          p.success {
            value should be(1)
          }
      }
    }
    p.future
  }

  "TryTMonadError point with exception" in {
    val tryTFutureInt: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].point {
      throw Boom()
      1
    }

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

  "TryTMonadError raiseError" in {
    val tryTFutureInt: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].raiseError(Boom())

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

  "TryTMonadError handleError" in {

    val error: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].raiseError(Boom())

    val tryTFutureInt: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].handleError(error) { throwable =>
      MonadError[TryT[Future, ?], Throwable].point(1)
    }

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Success(value) =>
          p.success {
            value should be(1)
          }
      }
    }
    p.future
  }

  "TryTMonadError handleError -- when handler throw exception" in {

    val error: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].raiseError(Boom())

    val tryTFutureInt: TryT[Future, Int] = MonadError[TryT[Future, ?], Throwable].handleError(error) { throwable =>
      throw throwable
      MonadError[TryT[Future, ?], Throwable].point(1)
    }

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

  implicit object FutureBindRec extends BindRec[Future] {
    override def tailrecM[A, B](f: (A) => Future[\/[A, B]])(a: A): Future[B] = {
      f(a).flatMap {
        case \/-(b) => Future.futureInstance.point(b)
        case -\/(a) => tailrecM(f)(a)
      }
    }

    override def bind[A, B](fa: Future[A])(f: (A) => Future[B]): Future[B] = Future.futureInstance.bind(fa)(f)

    override def map[A, B](fa: Future[A])(f: (A) => B): Future[B] = Future.futureInstance.map(fa)(f)
  }

  "TryTBindRec with failure" in {

    val tryTFutureInt: TryT[Future, Int] = BindRec[TryT[Future, ?]].tailrecM { a: Int =>
      TryT[Future, Int \/ Int](
        Future.now(
          Failure[Int \/ Int](
            Boom()
          )))
    }(0)

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

  "TryTBindRec f throw exception" in {

    val tryTFutureInt: TryT[Future, Int] = BindRec[TryT[Future, ?]].tailrecM { a: Int =>
      throw AnotherBoom()
      TryT(
        Future.now(Try[Int \/ Int](
          try {
            \/-(a)
          } catch {
            case NonFatal(_) => -\/(1)
          }
        )))
    }(0)

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[AnotherBoom])
          }
      }
    }
    p.future
  }

  "TryTBindRec should not stackOverFlow" in {

    var flag: Int = 0

    val tryTFutureInt: TryT[Future, Int] = BindRec[TryT[Future, ?]].tailrecM { a: Int =>
      TryT(
        Future.now(Try[Int \/ Int](
          try {
            if (flag < 10000) {
              flag += 1
              -\/(a)
            } else {
              \/-(flag)
            }
          } catch {
            case NonFatal(_) => -\/(1)
          }
        )))
    }(0)

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(tryTFutureInt)

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Success(value) =>
          p.success {
            value should be(10000)
          }
      }
    }
    p.future
  }

  "TryTParallelApplicative point without exception" in {

    val tryTFutureInt: TryT[Future, Int] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point(1)

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(Parallel.unwrap(tryTFutureInt))

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Success(value) =>
          p.success {
            value should be(1)
          }
      }
    }
    p.future
  }

  "TryTParallelApplicative point with exception" in {

    val tryTFutureInt: TryT[Future, Int] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point {
        throw Boom()
        1
      }

    val futureTryInt: Future[Try[Int]] = TryT.unwrap(Parallel.unwrap(tryTFutureInt))

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

  "TryTParallelApplicative ap without exception" in {

    def fa: TryT[Future, Int] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point(1)

    def f: TryT[Future, Int => String] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point { int =>
        "String"
      }

    val tryTFutureInt: TryT[Future, String] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].ap(fa)(f)

    val futureTryInt: Future[Try[String]] = TryT.unwrap(Parallel.unwrap(tryTFutureInt))

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Success(value) =>
          p.success {
            value should be("String")
          }
      }
    }
    p.future
  }

  "TryTParallelApplicative ap with exception" in {

    def fa: TryT[Future, Int] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point(1)

    def f: TryT[Future, Int => String] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].point { int =>
        throw Boom()
        "String"
      }

    val tryTFutureInt: TryT[Future, String] @@ Parallel =
      Applicative[Lambda[x => TryT[Future, x] @@ Parallel]].ap(fa)(f)

    val futureTryInt: Future[Try[String]] = TryT.unwrap(Parallel.unwrap(tryTFutureInt))

    val p = Promise[Assertion]

    futureTryInt.unsafePerformAsync { tryInt =>
      inside(tryInt) {
        case Failure(e) =>
          p.success {
            e should be(a[Boom])
          }
      }
    }
    p.future
  }

}
