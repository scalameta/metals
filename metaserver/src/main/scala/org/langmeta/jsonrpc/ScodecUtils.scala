package org.langmeta.jsonrpc

import java.nio.charset.StandardCharsets.US_ASCII

import scodec._
import scodec.bits._
import scodec.codecs._

object ScodecUtils {
  //parse out ascii string ignoring prefix and terminator
  def bracketedBy(prefix: String, terminator: String): Codec[String] =
    filtered(
      ascii,
      new Codec[BitVector] {
        val maxLen = 8L * 1024 * 1024 //Arbitrarily chosen limit to prevent malformed messages consuming all server memory
        val startBits = ByteVector(prefix.getBytes(US_ASCII)).bits
        val startBitsLen = startBits.length
        val terminatorBits = ByteVector(terminator.getBytes(US_ASCII)).bits
        val terminatorBitsLen = terminatorBits.length
        override def sizeBound: SizeBound = SizeBound.unknown
        override def encode(bits: BitVector): Attempt[BitVector] =
          Attempt.successful(startBits ++ bits ++ terminatorBits)
        override def decode(
            bits: BitVector
        ): Attempt[DecodeResult[BitVector]] = {
          val bitsLen = bits.length
          bits.acquire(startBitsLen) match {
            case Right(aquired) if (aquired == startBits) =>
              bits.drop(startBitsLen).indexOfSlice(terminatorBits) match {
                case -1 =>
                  if (bitsLen >= maxLen)
                    Attempt.failure(Err("Does not contain terminator."))
                  else
                    Attempt.failure(
                      Err(s"terminator not found in $bitsLen bits")
                    )
                case tIdx =>
                  val found = bits.slice(startBitsLen, startBitsLen + tIdx)
                  val remainder =
                    bits.drop(startBitsLen + tIdx + terminatorBitsLen)
                  Attempt.successful(DecodeResult(found, remainder))
              }
            case Right(aquired) =>
              Attempt.failure(Err("Does not start with prefix."))
            case Left(errString) => Attempt.failure(Err(errString))
          }
        }
      }
    )

  //patch scodec lookahead to prevent encoding lookahead bits, they are meant to be looked at, not encoded or decoded
  def lookaheadIssue98Patch(codec: Codec[Unit]): Codec[Boolean] =
    new Codec[Boolean] {
      def encode(value: Boolean): Attempt[BitVector] =
        Attempt.successful(BitVector.empty) //workaround https://github.com/scodec/scodec/issues/98
      def sizeBound: SizeBound = SizeBound.unknown
      def decode(bits: BitVector): Attempt[DecodeResult[Boolean]] =
        codec
          .decode(bits)
          .map {
            _.map { _ =>
              true
            }.mapRemainder(_ => bits)
          }
          .recover { case _ => DecodeResult(false, bits) }
    }

}
