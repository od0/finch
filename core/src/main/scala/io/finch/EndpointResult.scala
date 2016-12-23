package io.finch

import com.twitter.util.{Await, Duration, Try}
import io.catbird.util.Rerunnable

/**
 * A result returned from an [[Endpoint]].
 *
 * An endpoint could be either _match_ or _mismatched_ (skipped).
 *
 * This class also provides various of `awaitX` methods useful for testing and experimenting.
 */
sealed abstract class EndpointResult[A] {

  /**
   * Whether the [[Endpoint]] is matched on a given [[Input]].
   */
  def isMatched: Boolean

  /**
   * Returns the remainder of the [[Input]] after an [[Endpoint]] is matched.
   *
   * @return `Some(remainder)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  final def remainder: Option[Input] = this match {
    case m: EndpointResult.Matched[_] => Some(m.rem)
    case _ => None
  }

  /**
   * Awaits for an [[Output]] wrapped with [[Try]] (indicating if the [[com.twitter.util.Future]]
   * is failed).
   *
   * @note This method is blocking. Never use it in production.
   *
   * @return `Some(output)` if this endpoint was matched on a given input, `None` otherwise.
   */
  final def awaitOutput(d: Duration = Duration.Top): Option[Try[Output[A]]] = this match {
    case m: EndpointResult.Matched[A] => Some(Await.result(m.out.liftToTry.run, d))
    case _ => None
  }

  /**
   * Awaits an [[Output]] of the [[Endpoint]] result or throws an exception if an underlying
   * [[com.twitter.util.Future]] is failed.
   *
   * @note This method is blocking. Never use it in production.
   *
   * @return `Some(output)` if this endpoint was matched on a given input, `None` otherwise.
   */
  final def awaitOutputUnsafe(d: Duration = Duration.Top): Option[Output[A]] =
    awaitOutput(d).map(toa => toa.get)

  /**
   * Awaits a value from the [[Output]] wrapped with [[Try]] (indicating if either the
   * [[com.twitter.util.Future]] is failed or [[Output]] wasn't a payload).
   *
   * @note This method is blocking. Never use it in production.
   *
   * @return `Some(value)` if this endpoint was matched on a given input, `None` otherwise.
   */
  final def awaitValue(d: Duration = Duration.Top): Option[Try[A]] =
    awaitOutput(d).map(toa => toa.flatMap(oa => Try(oa.value)))

  /**
   * Awaits a value from the [[Output]] or throws an exception if either an underlying
   * [[com.twitter.util.Future]] is failed or [[Output]] wasn't a payload.
   *
   * @note @note This method is blocking. Never use it in production.
   *
   * @return `Some(value)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  final def awaitValueUnsafe(d: Duration = Duration.Top): Option[A] =
    awaitOutputUnsafe(d).map(oa => oa.value)

  /**
   * Queries an [[Output]] wrapped with [[Try]] (indicating if the [[com.twitter.util.Future]] is
   * failed).
   *
   * @note This method is blocking and awaits on the underlying [[com.twitter.util.Future]] with
   *       the upper bound of 10 seconds.
   *
   * @return `Some(output)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  @deprecated("Use awaitOutput(Duration) instead", "0.12")
  final def tryOutput: Option[Try[Output[A]]] = awaitOutput(Duration.fromSeconds(10))

  /**
   * Queries a value from the [[Output]] wrapped with [[Try]] (indicating if either the
   * [[com.twitter.util.Future]] is failed or [[Output]] wasn't a payload).
   *
   * @note This method is blocking and awaits on the underlying [[com.twitter.util.Future]] with
   *       the upper bound of 10 seconds.
   *
   * @return `Some(value)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  @deprecated("Use awaitValue(Duration) instead", "0.12")
  final def tryValue: Option[Try[A]] = awaitValue(Duration.fromSeconds(10))

  /**
   * Queries an [[Output]] of the [[Endpoint]] result or throws an exception if an underlying
   * [[com.twitter.util.Future]] is failed.
   *
   * @note This method is blocking and awaits on the underlying [[com.twitter.util.Future]]
   *       with the upper bound of 10 seconds.
   *
   * @return `Some(output)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  @deprecated("Use awaitOutputUnsafe(Duration) instead",  "0.12")
  final def output: Option[Output[A]] = awaitOutputUnsafe(Duration.fromSeconds(10))

  /**
   * Queries the value from the [[Output]] or throws an exception if either an underlying
   * [[com.twitter.util.Future]] is failed or [[Output]] wasn't a payload.
   *
   * @note This method is blocking and awaits on the underlying [[com.twitter.util.Future]] with
   *       the upper bound of 10 seconds.
   *
   * @return `Some(value)` if this endpoint was matched on a given input,
   *         `None` otherwise.
   */
  @deprecated("Use awaitValueUnsafe instead", "0.12")
  final def value: Option[A] = awaitValueUnsafe(Duration.fromSeconds(10))
}

object EndpointResult {

  private case object Empty extends EndpointResult[Nothing] {
    def isMatched: Boolean = false
  }

  private[finch] final class Matched[A](
      val rem: Input, var o: Rerunnable[_]) extends EndpointResult[A] {

    def out: Rerunnable[Output[A]] = o.asInstanceOf[Rerunnable[Output[A]]]
    def isMatched: Boolean = true
  }

  /**
   * Builds new [[EndpointResult]].
   */
  def apply[A](rem: Input, out: Rerunnable[Output[A]]): EndpointResult[A] = new Matched[A](rem, out)

  /**
   * Builds new empty (not matched) [[EndpointResult]].
   */
  def empty[A]: EndpointResult[A] = Empty.asInstanceOf[EndpointResult[A]]
}
