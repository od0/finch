package io.finch

import com.twitter.finagle.Service
import com.twitter.finagle.httpx.{Request, Response}
import com.twitter.util.Future
import io.finch.request._
import io.finch.route._
import shapeless._
import shapeless.ops.adjoin.Adjoin
import shapeless.ops.function.FnToProduct

/**
 * A router that extracts some value of the type `A` from the given route.
 */
trait Endpoint[A] { self =>
  import Endpoint._

  /**
   * Maps this [[Endpoint]] to either `A => B` or `A => Future[B]`.
   */
  def apply(mapper: Mapper[A]): Endpoint[mapper.Out] = mapper(self)

  /**
   * Extracts some value of type `A` from the given `input`.
   */
  def apply(input: Input): Option[(Input, () => Future[A])]

  /**
   * Maps this router to the given function `A => B`.
   */
  def map[B](fn: A => B): Endpoint[B] = new Endpoint[B] {
    def apply(input: Input): Option[(Input, () => Future[B])] =
      self(input).map {
        case (input, result) => (input, () => result().map(fn))
      }

    override def toString = self.toString
  }

  /**
   * Flat-maps the router to the given function `A => Future[B]`. If the given function `None` the resulting router will
   * also return `None`.
   */
  def embedFlatMap[B](fn: A => Future[B]): Endpoint[B] = new Endpoint[B] {
    def apply(input: Input): Option[(Input, () => Future[B])] =
      self(input).map {
        case (input, result) => (input, () => result().flatMap(fn))
      }

    override def toString = self.toString
  }

  /**
   * Flat-maps this router to the given function `A => Router[B]`.
   */
  def ap[B](fn: Endpoint[A => B]): Endpoint[B] = new Endpoint[B] {
    def apply(input: Input): Option[(Input, () => Future[B])] =
      self(input).flatMap {
        case (input1, resultA) => fn(input1).map {
          case (input2, resultF) => (
            input2,
            () => resultA().join(resultF()).map {
              case (a, f) => f(a)
            }
          )
        }
      }

    override def toString = self.toString
  }

  /**
   * Composes this router with the given `that` router. The resulting router will succeed only if both this and `that`
   * routers succeed.
   */
  def /[B](that: Endpoint[B])(implicit adjoin: PairAdjoin[A, B]): Endpoint[adjoin.Out] =
    new Endpoint[adjoin.Out] {
      val inner = self.ap(
        that.map { b => (a: A) => adjoin(a, b) }
      )
      def apply(input: Input): Option[(Input, () => Future[adjoin.Out])] = inner(input)

      override def toString = s"${self.toString}/${that.toString}"
    }

  /**
   * Composes this router with the given [[io.finch.request.RequestReader]].
   */
  def ?[B](that: RequestReader[B])(implicit adjoin: PairAdjoin[A, B]): Endpoint[adjoin.Out] =
    new Endpoint[adjoin.Out] {
      def apply(input: Input): Option[(Input, () => Future[adjoin.Out])] =
        self(input).map {
          case (input, result) => (
            input,
            () => result().join(that(input.request)).map {
              case (a, b) => adjoin(a, b)
            }
          )
        }

      override def toString = s"${self.toString}?${that.toString}"
    }

  /**
   * Sequentially composes this router with the given `that` router. The resulting router will succeed if either this or
   * `that` routers are succeed.
   */
  def |[B >: A](that: Endpoint[B]): Endpoint[B] = new Endpoint[B] {
    def apply(input: Input): Option[(Input, () => Future[B])] = (self(input), that(input)) match {
      case (aa @ Some((a, _)), bb @ Some((b, _))) =>
        if (a.path.length <= b.path.length) aa else bb
      case (a, b) => a orElse b
    }

    override def toString = s"(${self.toString}|${that.toString})"
  }

  // A workaround for https://issues.scala-lang.org/browse/SI-1336
  def withFilter(p: A => Boolean): Endpoint[A] = self

  /**
   * Compose this router with another in such a way that coproducts are flattened.
   */
  def :+:[B](that: Endpoint[B])(implicit adjoin: Adjoin[B :+: A :+: CNil]): Endpoint[adjoin.Out] =
    that.map(b => adjoin(Inl[B, A :+: CNil](b))) |
    self.map(a => adjoin(Inr[B, A :+: CNil](Inl[A, CNil](a))))

  /**
   * Converts this router to a Finagle service from a request-like type `R` to a
   * [[com.twitter.finagle.httpx.Response]].
   */
  def toService(implicit ts: ToService[A]): Service[Request, Response] = ts(this)
}

/**
 * Provides extension methods for [[Endpoint]] to support coproduct and path
 * syntax.
 */
object Endpoint {

  /**
   * An input for [[Endpoint]].
   */
  final case class Input(request: Request, path: Seq[String]) {
    def headOption: Option[String] = path.headOption
    def drop(n: Int): Input = copy(path = path.drop(n))
    def isEmpty: Boolean = path.isEmpty
  }

  /**
   * Creates an input for [[Endpoint]] from [[com.twitter.finagle.httpx.Request]].
   */
  def Input(req: Request): Input = Input(req, req.path.split("/").toList.drop(1))

  /**
   * Creates a [[Endpoint]] from the given [[Future]] `f`.
   */
  def const[A](f: Future[A]): Endpoint[A] = embed(input => Some((input, () => f)))

  /**
   * Creates a [[Endpoint]] from the given value `v`.
   */
  def value[A](v: A): Endpoint[A] = const(Future.value(v))

  /**
   * Creates a [[Endpoint]] from the given exception `exc`.
   */
  def exception[A](exc: Throwable): Endpoint[A] = const(Future.exception(exc))

  /**
   * Creates a [[Endpoint]] from the given function `Input => Output[A]`.
   */
  private[finch] def embed[A](fn: Input => Option[(Input, () => Future[A])]): Endpoint[A] = new Endpoint[A] {
    def apply(input: Input): Option[(Input, () => Future[A])] = fn(input)
  }

  /**
   * Add `/>` and `/>>` compositors to `Router` to compose it with function of one argument.
   */
  @deprecated("Use smart apply (Endpoint.apply) instead", "0.9.0")
  implicit class RArrow1[A](r: Endpoint[A]) {
    def />[B](fn: A => B): Endpoint[B] = r.map(fn)
    def />>[B](fn: A => Future[B]): Endpoint[B] = r.embedFlatMap(fn)
  }

  /**
   * Add `/>` and `/>>` compositors to `Router` to compose it with values.
   */
  @deprecated("Use smart apply (Endpoint.apply) instead", "0.9.0")
  implicit class RArrow0(r: Router0) {
    def />[B](v: => B): Endpoint[B] = r.map(_ => v)
    def />>[B](v: => Future[B]): Endpoint[B] = r.embedFlatMap(_ => v)
  }

  /**
   * Add `/>` and `/>>` compositors to `Router` to compose it with function of two arguments.
   */
  @deprecated("Use smart apply (Endpoint.apply) instead", "0.9.0")
  implicit class RArrow2[A, B](r: Router2[A, B]) {
    def />[C](fn: (A, B) => C): Endpoint[C] = r.map {
      case a :: b :: HNil => fn(a, b)
    }

    def />>[C](fn: (A, B) => Future[C]): Endpoint[C] = r.embedFlatMap {
      case a :: b :: HNil => fn(a, b)
    }
  }

  /**
   * Add `/>` and `/>>` compositors to `Router` to compose it with function of three arguments.
   */
  @deprecated("Use smart apply (Endpoint.apply) instead", "0.9.0")
  implicit class RArrow3[A, B, C](r: Router3[A, B, C]) {
    def />[D](fn: (A, B, C) => D): Endpoint[D] = r.map {
      case a :: b :: c :: HNil => fn(a, b, c)
    }

    def />>[D](fn: (A, B, C) => Future[D]): Endpoint[D] = r.embedFlatMap {
      case a :: b :: c :: HNil => fn(a, b, c)
    }
  }

  /**
   * Add `/>` and `/>>` compositors to `Router` to compose it with function of N arguments.
   */
  @deprecated("Use smart apply (Endpoint.apply) instead", "0.9.0")
  implicit class RArrowN[L <: HList](r: Endpoint[L]) {
    def />[F, I](fn: F)(implicit ftp: FnToProduct.Aux[F, L => I]): Endpoint[I] =
      r.map(ftp(fn))

    def />>[F, I, FI](fn: F)(
      implicit ftp: FnToProduct.Aux[F, L => FI], ev: FI <:< Future[I]
    ): Endpoint[I] = r.embedFlatMap(value => ev(ftp(fn)(value)))
  }
}
