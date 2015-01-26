package ch.ethz.dalab.dissolve.examples.imageseg

import org.apache.log4j.PropertyConfigurator
import breeze.linalg.{ Matrix, Vector }
import ch.ethz.dalab.dissolve.classification.StructSVMModel
import breeze.linalg.DenseVector
import breeze.linalg.DenseMatrix
import cc.factorie.variable.DiscreteDomain
import cc.factorie.variable.DiscreteVariable
import breeze.linalg.sum
import breeze.linalg.Axis
import cc.factorie.model.Factor1
import cc.factorie.model.Factor

case class ROIFeature(feature: Vector[Double]) // Represent each pixel/region by a feature vector

case class ROILabel(label: Int, numClasses: Int = 24) {
  override def equals(o: Any) = o match {
    case that: ROILabel => that.label == this.label
    case _              => false
  }
}

object ImageSegmentationDemo {

  def getUnaryFeatureMap(yMat: Matrix[ROILabel], xMat: Matrix[ROIFeature]): DenseMatrix[Double] = {
    assert(xMat.rows == yMat.rows)
    assert(xMat.cols == yMat.cols)

    val numFeatures = xMat(0, 0).feature.size
    val numClasses = yMat(0, 0).numClasses
    val numRegions = xMat.rows * xMat.cols

    val unaryMat = DenseMatrix.zeros[Double](numFeatures * numClasses, numRegions)

    /**
     * Populate unary features
     * For each node i in graph defined by xMat, whose feature vector is x_i and corresponding label is y_i,
     * construct a feature map phi_i given by: [I(y_i = 0)x_i I(y_i = 1)x_i ... I(y_i = K)x_i ]
     */

    for (
      r <- 0 until xMat.rows;
      c <- 0 until xMat.cols
    ) {
      val i = r * xMat.rows + c // Column-major iteration

      val x_i = xMat(r, c).feature
      val y_i = yMat(r, c).label

      val phi_i = DenseVector.zeros[Double](numFeatures * numClasses)

      val startIdx = numFeatures * y_i
      val endIdx = startIdx + numFeatures

      // For y_i'th position of phi_i, populate x_i's feature vector
      phi_i(startIdx until endIdx) := x_i

      unaryMat(::, i) := phi_i
    }

    unaryMat
  }

  def getPairwiseFeatureMap(yMat: Matrix[ROILabel], xMat: Matrix[ROIFeature]): DenseMatrix[Double] = {
    assert(xMat.rows == yMat.rows)
    assert(xMat.cols == yMat.cols)

    val numFeatures = xMat(0, 0).feature.size
    val numClasses = yMat(0, 0).numClasses
    val numRegions = xMat.rows * xMat.cols

    val pairwiseMat = DenseMatrix.zeros[Double](numClasses, numClasses)

    for (
      c <- 1 until xMat.cols - 1;
      r <- 1 until xMat.rows - 1
    ) {
      val classA = yMat(c, r).label

      for (
        delx <- List(-1, 0, 1);
        dely <- List(-1, 0, 1) if ((delx != 0) && (dely != 0))
      ) {
        val classB = yMat(c + delx, r + dely).label

        pairwiseMat(classA, classB) += 1.0
        pairwiseMat(classB, classA) += 1.0
      }
    }

    pairwiseMat
  }

  /**
   * Feature Function.
   * Uses: http://arxiv.org/pdf/1408.6804v2.pdf
   * http://www.kev-smith.com/papers/LUCCHI_ECCV12.pdf
   */
  def featureFn(yMat: Matrix[ROILabel], xMat: Matrix[ROIFeature]): Vector[Double] = {

    assert(xMat.rows == yMat.rows)
    assert(xMat.cols == yMat.cols)

    val unaryMat = getUnaryFeatureMap(yMat, xMat)
    val pairwiseMat = getPairwiseFeatureMap(yMat, xMat)

    val unarySumVec = sum(unaryMat, Axis._1)
    DenseVector.vertcat(unarySumVec, pairwiseMat.toDenseVector)
  }

  /**
   * Loss function
   */
  def lossFn(yTruth: Matrix[ROILabel], yPredict: Matrix[ROILabel]): Double = {

    assert(yTruth.rows == yPredict.rows)
    assert(yTruth.cols == yPredict.cols)

    val loss =
      for (
        x <- 0 until yTruth.cols;
        y <- 0 until yTruth.rows
      ) yield {
        if (x == y) 0.0 else 1.0
      }

    loss.sum
  }

  /**
   * Oracle function
   */
  def oracleFn(model: StructSVMModel[Matrix[ROIFeature], Matrix[ROILabel]], yi: Matrix[ROILabel], xi: Matrix[ROIFeature]): Matrix[ROILabel] = {

    assert(xi.rows == yi.rows)
    assert(xi.cols == yi.cols)

    val numClasses = yi(0, 0).numClasses
    val numRows = xi.rows
    val numCols = xi.cols
    val numROI = numRows * numCols
    val xFeatureSize = xi(0, 0).feature.size

    val weightVec = model.getWeights()

    val unaryStartIdx = 0
    val unaryEndIdx = xFeatureSize * numClasses
    // This should be a vector of dimensions 1 x (K x h), where K = #Classes, h = dim(x_i)
    val unary: Vector[Double] = weightVec(unaryStartIdx until unaryEndIdx)

    val pairwiseStartIdx = unaryEndIdx
    val pairwiseEndIdx = weightVec.size
    assert(pairwiseEndIdx - pairwiseStartIdx == numClasses * numClasses)
    val pairwise: DenseMatrix[Double] = weightVec(pairwiseStartIdx until pairwiseEndIdx)
      .toDenseVector
      .toDenseMatrix
      .reshape(numClasses, numClasses)

    val phi_Y: DenseMatrix[Double] = getUnaryFeatureMap(yi, xi) // Retrieves a (K x h) x m matrix
    val thetaUnary = phi_Y * unary // Construct a (1 x m) vector
    val thetaPairwise = pairwise

    /**
     * Parameter estimation
     */
    object ROIDomain extends DiscreteDomain(numClasses)

    class ROIClassVar(i: Int) extends DiscreteVariable(i) {
      def domain = ROIDomain
    }

    def getUnaryFactor(yi: ROIClassVar, x: Int, y: Int): Factor = {
      new Factor1(yi) {
        def score(i: ROIClassVar#Value) = {
          val unaryStartIdx = i.intValue
          val unaryEndIdx = unaryStartIdx + xFeatureSize
          val unaryPotAtxy = xi(x, y).feature dot unary(unaryStartIdx until unaryEndIdx)

          unaryPotAtxy
        }
      }
    }

    null
  }

  /**
   * Prediction Function
   */
  def predictFn(model: StructSVMModel[Matrix[ROIFeature], Matrix[ROILabel]], xi: Matrix[ROIFeature]): Matrix[ROILabel] = {

    null
  }

  def dissolveImageSementation(options: Map[String, String]) {

  }

  def main(args: Array[String]): Unit = {
    PropertyConfigurator.configure("conf/log4j.properties")

    val options: Map[String, String] = args.map { arg =>
      arg.dropWhile(_ == '-').split('=') match {
        case Array(opt, v) => (opt -> v)
        case Array(opt)    => (opt -> "true")
        case _             => throw new IllegalArgumentException("Invalid argument: " + arg)
      }
    }.toMap

    System.setProperty("spark.akka.frameSize", "512")
    println(options)

  }

}