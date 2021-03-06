/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.sparkbindings.drm.decompositions

import org.scalatest.{Matchers, FunSuite}
import org.apache.mahout.sparkbindings.test.MahoutLocalContext
import org.apache.mahout.math.scalabindings._
import RLikeOps._
import org.apache.mahout.sparkbindings.drm._
import RLikeDrmOps._
import scala.util.Random
import org.apache.mahout.math.{Matrices, SparseRowMatrix}
import org.apache.spark.storage.StorageLevel
import org.apache.mahout.common.RandomUtils

/**
 *
 * @author dmitriy
 */
class MathSuite extends FunSuite with Matchers with MahoutLocalContext {

  test("thin distributed qr") {

    val inCoreA = dense(
      (1, 2, 3, 4),
      (2, 3, 4, 5),
      (3, -4, 5, 6),
      (4, 5, 6, 7),
      (8, 6, 7, 8)
    )

    val A = drmParallelize(inCoreA, numPartitions = 2)
    val (drmQ, inCoreR) = dqrThin(A, checkRankDeficiency = false)

    // Assert optimizer still knows Q and A are identically partitioned
    drmQ.partitioningTag should equal (A.partitioningTag)

    drmQ.rdd.partitions.size should be(A.rdd.partitions.size)

    // Should also be zippable
    drmQ.rdd.zip(other = A.rdd)

    val inCoreQ = drmQ.collect

    printf("A=\n%s\n", inCoreA)
    printf("Q=\n%s\n", inCoreQ)
    printf("R=\n%s\n", inCoreR)

    val (qControl, rControl) = qr(inCoreA)
    printf("qControl=\n%s\n", qControl)
    printf("rControl=\n%s\n", rControl)

    // Validate with Cholesky
    val ch = chol(inCoreA.t %*% inCoreA)
    printf("A'A=\n%s\n", inCoreA.t %*% inCoreA)
    printf("L:\n%s\n", ch.getL)

    val rControl2 = (ch.getL cloned).t
    val qControl2 = ch.solveRight(inCoreA)
    printf("qControl2=\n%s\n", qControl2)
    printf("rControl2=\n%s\n", rControl2)

    // Housholder approach seems to be a little bit more stable
    (rControl - inCoreR).norm should be < 1E-5
    (qControl - inCoreQ).norm should be < 1E-5

    // Assert identicity with in-core Cholesky-based -- this should be tighter.
    (rControl2 - inCoreR).norm should be < 1E-10
    (qControl2 - inCoreQ).norm should be < 1E-10

    // Assert orhtogonality:
    // (a) Q[,j] dot Q[,j] == 1.0 for all j
    // (b) Q[,i] dot Q[,j] == 0.0 for all i != j
    for (col <- 0 until inCoreQ.ncol)
      ((inCoreQ(::, col) dot inCoreQ(::, col)) - 1.0).abs should be < 1e-10
    for (col1 <- 0 until inCoreQ.ncol - 1; col2 <- col1 + 1 until inCoreQ.ncol)
      (inCoreQ(::, col1) dot inCoreQ(::, col2)).abs should be < 1e-10


  }

  test("dssvd - the naive-est - q=0") {
    dssvdNaive(q = 0)
  }

  test("ddsvd - naive - q=1") {
    dssvdNaive(q = 1)
  }

  test("ddsvd - naive - q=2") {
    dssvdNaive(q = 2)
  }


  def dssvdNaive(q: Int) {
    val inCoreA = dense(
      (1, 2, 3, 4),
      (2, 3, 4, 5),
      (3, -4, 5, 6),
      (4, 5, 6, 7),
      (8, 6, 7, 8)
    )
    val drmA = drmParallelize(inCoreA, numPartitions = 2)

    val (drmU, drmV, s) = dssvd(drmA, k = 4, q = q)
    val (inCoreU, inCoreV) = (drmU.collect, drmV.collect)

    printf("U:\n%s\n", inCoreU)
    printf("V:\n%s\n", inCoreV)
    printf("Sigma:\n%s\n", s)

    (inCoreA - (inCoreU %*%: diagv(s)) %*% inCoreV.t).norm should be < 1E-5
  }

  test("dspca") {

    import math._

    val rnd = RandomUtils.getRandom

    // Number of points
    val m =  500
    // Length of actual spectrum
    val spectrumLen = 40

    val spectrum = dvec((0 until spectrumLen).map(x => 300.0 * exp(-x) max 1e-3))
    printf("spectrum:%s\n", spectrum)

    val (u, _) = qr(new SparseRowMatrix(m, spectrumLen) :=
        ((r, c, v) => if (rnd.nextDouble() < 0.2) 0 else rnd.nextDouble() + 5.0))

    // PCA Rotation matrix -- should also be orthonormal.
    val (tr, _) = qr(Matrices.symmetricUniformView(spectrumLen, spectrumLen, rnd.nextInt) - 10.0)

    val input = (u %*%: diagv(spectrum)) %*% tr.t
    val drmInput = drmParallelize(m = input, numPartitions = 2)

    // Calculate just first 10 principal factors and reduce dimensionality.
    // Since we assert just validity of the s-pca, not stochastic error, we bump p parameter to
    // ensure to zero stochastic error and assert only functional correctness of the method's pca-
    // specific additions.
    val k = 10

    // Calculate just first 10 principal factors and reduce dimensionality.
    var (drmPCA, _, s) = dspca(A = drmInput, k = 10, p = spectrumLen, q = 1)
    // Un-normalized pca data:
    drmPCA = drmPCA %*% diagv(s)

    val pca = drmPCA.checkpoint(sLevel = StorageLevel.NONE).collect

    // Of course, once we calculated the pca, the spectrum is going to be different since our originally
    // generated input was not centered. So here, we'd just brute-solve pca to verify
    val xi = input.colMeans()
    for (r <- 0 until input.nrow) input(r, ::) -= xi
    var (pcaControl, _, sControl) = svd(m = input)
    pcaControl = (pcaControl %*%: diagv(sControl))(::, 0 until k)

    printf("pca:\n%s\n", pca(0 until 10, 0 until 10))
    printf("pcaControl:\n%s\n", pcaControl(0 until 10, 0 until 10))

    (pca(0 until 10, 0 until 10).norm - pcaControl(0 until 10, 0 until 10).norm).abs should be < 1E-5

  }


}
