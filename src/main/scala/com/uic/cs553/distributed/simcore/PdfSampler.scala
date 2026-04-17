package com.uic.cs553.distributed.simcore

import scala.util.Random

/**
 * Samples message types from a NodePdf using a seeded random generator.
 *
 * Using a fixed seed makes experiments REPRODUCIBLE — same seed = same
 * sequence of messages every run. This is required by the grading rubric.
 *
 * This is like a weighted random selector:
 *   PDF: {PING: 0.5, GOSSIP: 0.3, WORK: 0.2}
 *   Roll 0.3 → PING  (falls in 0.0-0.5 range)
 *   Roll 0.7 → GOSSIP (falls in 0.5-0.8 range)
 *   Roll 0.9 → WORK  (falls in 0.8-1.0 range)
 *
 * @param seed random seed for reproducibility
 */
class PdfSampler(seed: Long):
  private val rng = Random(seed)

  /**
   * Sample one message type from a NodePdf.
   * Uses cumulative probability (roulette wheel selection).
   */
  def sample(pdf: NodePdf): MessageType =
    val roll = rng.nextDouble()
    pdf.weights
      .toList
      .scanLeft(0.0): (cum, entry) =>
        cum + entry._2
      .zip(pdf.weights.toList.map(_._1))
      .drop(1)
      .find((cum, _) => roll <= cum)
      .map(_._2)
      .getOrElse(pdf.weights.keys.last)

  /**
   * Sample n message types from a NodePdf.
   */
  def sampleN(pdf: NodePdf, n: Int): List[MessageType] =
    List.fill(n)(sample(pdf))

object PdfSampler:
  /** Default sampler with fixed seed 42 — for reproducible experiments */
  val default: PdfSampler = PdfSampler(42L)