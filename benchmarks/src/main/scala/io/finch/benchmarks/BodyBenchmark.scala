package io.finch.benchmarks

import com.twitter.io.Buf
import com.twitter.util.Return
import io.finch._
import io.finch.internal.BufText
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

@State(Scope.Benchmark)
class BodyBenchmark extends FinchBenchmark {

  implicit val decodeJsonAsString: Decode.Json[String] =
    Decode.json((b, cs) => Return(BufText.extract(b, cs)))

  final val i: Input = Input.post("/").withBody[Text.Plain](Buf.Utf8("x" * 1024))

  @Benchmark
  def jsonOption: Option[String] = jsonBodyOption[String].apply(i).awaitValueUnsafe().get

  @Benchmark
  def json: String = jsonBody[String].apply(i).awaitValueUnsafe().get

  @Benchmark
  def stringOption: Option[String] = stringBodyOption(i).awaitValueUnsafe().get

  @Benchmark
  def string: String = stringBody(i).awaitValueUnsafe().get

  @Benchmark
  def byteArrayOption: Option[Array[Byte]] = binaryBodyOption(i).awaitValueUnsafe().get

  @Benchmark
  def byteArray: Array[Byte] = binaryBody(i).awaitValueUnsafe().get
}
