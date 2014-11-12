package ch.ethz.dal.dbcfw.classification

import ch.ethz.dal.dbcfw.regression.LabeledObject
import breeze.linalg._
import ch.ethz.dal.dbcfw.optimization.SolverOptions
import org.apache.spark.SparkContext
import ch.ethz.dal.dbcfw.optimization.DBCFWSolver
import java.io.FileWriter
import ch.ethz.dal.dbcfw.optimization.SolverUtils
import scala.reflect.ClassTag

class StructSVMWithDBCFW[X, Y](
  val sc: SparkContext,
  val data: Vector[LabeledObject[X, Y]],
  val featureFn: (Y, X) => Vector[Double], // (y, x) => FeatureVector
  val lossFn: (Y, Y) => Double, // (yTruth, yPredict) => LossValue
  val oracleFn: (StructSVMModel[X, Y], Y, X) => Y, // (model, y_i, x_i) => Label
  val predictFn: (StructSVMModel[X, Y], X) => Y,
  val solverOptions: SolverOptions[X, Y]) {

  def trainModel()(implicit m: ClassTag[Y]): StructSVMModel[X, Y] = {
    val (trainedModel, debugInfo) = new DBCFWSolver[X, Y](sc,
      data,
      featureFn,
      lossFn,
      oracleFn,
      predictFn,
      solverOptions,
      miniBatchEnabled = false).optimize()

    // Dump debug information into a file
    val fw = new FileWriter(solverOptions.debugInfoPath)
    // Write the current parameters being used
    fw.write(solverOptions.toString())
    fw.write("\n")
    
    // Write spark-specific parameters
    fw.write(SolverUtils.getSparkConfString(sc.getConf))
    fw.write("\n")
    
    // Write values noted from the run
    fw.write(debugInfo)
    fw.close()
    
    print(debugInfo)

    // Return the trained model
    trainedModel
  }
}