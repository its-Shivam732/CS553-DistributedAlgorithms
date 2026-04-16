package com.uic.cs553.distributed.framework

import com.uic.cs553.distributed.simcore.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests PDF sampling correctness and reproducibility.
 * Reproducibility under fixed seed is required by the grading rubric.
 */
class PdfSamplerTest extends AnyFunSuite with Matchers:

  test("NodePdf probabilities must sum to 1.0"):
    val pdf = NodePdf(Map(
      MessageType.PING   -> 0.50,
      MessageType.GOSSIP -> 0.30,
      MessageType.WORK   -> 0.20
    ))
    math.abs(pdf.weights.values.sum - 1.0) should be < 0.001

  test("NodePdf rejects probabilities that don't sum to 1.0"):
    assertThrows[IllegalArgumentException]:
      NodePdf(Map(
        MessageType.PING -> 0.50,
        MessageType.WORK -> 0.30
        // missing 0.20 — sums to 0.80
      ))

  test("PdfSampler always returns a valid message type"):
    val pdf     = NodePdf.uniform
    val sampler = PdfSampler(42L)
    val samples = List.fill(100)(sampler.sample(pdf))
    samples.foreach: s =>
      MessageType.values should contain(s)

  test("PdfSampler is reproducible under fixed seed"):
    val pdf      = NodePdf.uniform
    val sampler1 = PdfSampler(42L)
    val sampler2 = PdfSampler(42L)
    val samples1 = List.fill(20)(sampler1.sample(pdf))
    val samples2 = List.fill(20)(sampler2.sample(pdf))
    samples1 shouldEqual samples2

  test("PdfSampler produces different sequences for different seeds"):
    val pdf      = NodePdf.uniform
    val sampler1 = PdfSampler(42L)
    val sampler2 = PdfSampler(99L)
    val samples1 = List.fill(50)(sampler1.sample(pdf))
    val samples2 = List.fill(50)(sampler2.sample(pdf))
    samples1 should not equal samples2

  test("NodePdf.uniform gives equal weight to all message types"):
    val pdf = NodePdf.uniform
    val expectedP = 1.0 / MessageType.values.size
    pdf.weights.values.foreach: p =>
      math.abs(p - expectedP) should be < 0.001

  test("NodePdf.zipf gives decreasing probabilities"):
    val types = MessageType.values.toList
    val pdf   = NodePdf.zipf(types)
    val probs = types.map(pdf.weights)
    probs.zip(probs.tail).foreach: (higher, lower) =>
      higher should be > lower