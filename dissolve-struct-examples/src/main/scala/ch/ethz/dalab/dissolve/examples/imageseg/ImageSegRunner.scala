package ch.ethz.dalab.dissolve.examples.imageseg

import java.nio.file.Paths
import org.apache.log4j.PropertyConfigurator
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import ch.ethz.dalab.dissolve.classification.StructSVMModel
import ch.ethz.dalab.dissolve.classification.StructSVMWithDBCFW
import ch.ethz.dalab.dissolve.utils.cli.CLAParser
import javax.imageio.ImageIO

/**
 * @author torekond
 */
object ImageSegRunner {

  def main(args: Array[String]): Unit = {

    PropertyConfigurator.configure("conf/log4j.properties")
    System.setProperty("spark.akka.frameSize", "512")

    val startTime = System.currentTimeMillis() / 1000

    /**
     * Load all options
     */
    val (solverOptions, kwargs) = CLAParser.argsToOptions[QuantizedImage, QuantizedLabel](args)
    val dataDir = kwargs.getOrElse("input_path", "../data/generated/msrc")
    val appname = kwargs.getOrElse("appname", "imageseg-%d".format(startTime))
    val debugPath = kwargs.getOrElse("debug_file", "imageseg-%d.csv".format(startTime))

    val unariesOnly = kwargs.getOrElse("unaries", "true").toBoolean

    val trainFile = kwargs.getOrElse("train", "Train.txt")
    val validationFile = kwargs.getOrElse("validation", "Validation.txt")
    solverOptions.debugInfoPath = debugPath

    println(dataDir)
    println(kwargs)

    solverOptions.doLineSearch = true

    if (unariesOnly)
      ImageSeg.DISABLE_PAIRWISE = true

    /**
     * Setup Spark
     */
    val conf = new SparkConf().setAppName(appname).setMaster("local")
    val sc = new SparkContext(conf)
    sc.setCheckpointDir("checkpoint-files")

    val trainFilePath = Paths.get(dataDir, trainFile)
    val valFilePath = Paths.get(dataDir, validationFile)

    val trainDataSeq = ImageSegUtils.loadData(dataDir, trainFilePath)
    val valDataSeq = ImageSegUtils.loadData(dataDir, valFilePath)

    val trainData = sc.parallelize(trainDataSeq, 1).cache
    val valData = sc.parallelize(valDataSeq, 1).cache

    solverOptions.testDataRDD = Some(valData)

    println(solverOptions)

    val trainer: StructSVMWithDBCFW[QuantizedImage, QuantizedLabel] =
      new StructSVMWithDBCFW[QuantizedImage, QuantizedLabel](
        trainData,
        ImageSeg,
        solverOptions)

    val model: StructSVMModel[QuantizedImage, QuantizedLabel] = trainer.trainModel()

    // Create directories for image out, if it doesn't exist
    val imageOutDir = Paths.get(dataDir, "debug", appname)
    if (!imageOutDir.toFile().exists())
      imageOutDir.toFile().mkdirs()

    println("Test time!")
    for (lo <- trainDataSeq) {
      val t0 = System.currentTimeMillis()
      val prediction = model.predict(lo.pattern)
      val t1 = System.currentTimeMillis()

      val filename = lo.pattern.filename
      val format = "bmp"
      val outPath = Paths.get(imageOutDir.toString(), "train-%s.%s".format(filename, format))

      // Image
      val imgPath = Paths.get(dataDir.toString(), "All", "%s.bmp".format(filename))
      val img = ImageIO.read(imgPath.toFile())

      val width = img.getWidth()
      val height = img.getHeight()

      // Write loss info
      val predictTime: Long = t1 - t0
      val loss: Double = ImageSeg.lossFn(prediction, lo.label)
      val text = "filename = %s\nprediction time = %d ms\nerror = %f\n#spx = %d".format(filename, predictTime, loss, lo.pattern.unaries.cols)
      val textInfoImg = ImageSegUtils.getImageWithText(width, height, text)

      // GT
      val gtImage = ImageSegUtils.getQuantizedLabelImage(lo.label,
        lo.pattern.pixelMapping,
        lo.pattern.width,
        lo.pattern.height)

      // Prediction
      val predImage = ImageSegUtils.getQuantizedLabelImage(prediction,
        lo.pattern.pixelMapping,
        lo.pattern.width,
        lo.pattern.height)

      val prettyOut = ImageSegUtils.printImageTile(img, gtImage, textInfoImg, predImage)
      ImageSegUtils.writeImage(prettyOut, outPath.toString())

    }

    for (lo <- valDataSeq) {
      val t0 = System.currentTimeMillis()
      val prediction = model.predict(lo.pattern)
      val t1 = System.currentTimeMillis()

      val filename = lo.pattern.filename
      val format = "bmp"
      val outPath = Paths.get(imageOutDir.toString(), "val-%s.%s".format(filename, format))

      // Image
      val imgPath = Paths.get(dataDir.toString(), "All", "%s.bmp".format(filename))
      val img = ImageIO.read(imgPath.toFile())

      val width = img.getWidth()
      val height = img.getHeight()

      // Write loss info
      val predictTime: Long = t1 - t0
      val loss: Double = ImageSeg.lossFn(prediction, lo.label)
      val text = "filename = %s\nprediction time = %d ms\nerror = %f\n#spx = %d".format(filename, predictTime, loss, lo.pattern.unaries.cols)
      val textInfoImg = ImageSegUtils.getImageWithText(width, height, text)

      // GT
      val gtImage = ImageSegUtils.getQuantizedLabelImage(lo.label,
        lo.pattern.pixelMapping,
        lo.pattern.width,
        lo.pattern.height)

      // Prediction
      val predImage = ImageSegUtils.getQuantizedLabelImage(prediction,
        lo.pattern.pixelMapping,
        lo.pattern.width,
        lo.pattern.height)

      val prettyOut = ImageSegUtils.printImageTile(img, gtImage, textInfoImg, predImage)
      ImageSegUtils.writeImage(prettyOut, outPath.toString())

    }

    val unaryDebugPath =
      Paths.get("/home/torekond/dev-local/dissolve-struct/data/generated/msrc/debug",
        "%s-unary.csv".format(appname))
    val transDebugPath =
      Paths.get("/home/torekond/dev-local/dissolve-struct/data/generated/msrc/debug",
        "%s-trans.csv".format(appname))
    val weights = model.getWeights().toDenseVector

    val xi = trainDataSeq(0).pattern
    val d =
    if (xi.globalFeatures == null)
      xi.unaryFeatures.rows
    else
     xi.unaryFeatures.rows + xi.globalFeatures.size
    val (unaryMat, transMat) = ImageSeg.unpackWeightVec(weights, d)

    breeze.linalg.csvwrite(unaryDebugPath.toFile(), unaryMat)
    if (transMat != null)
      breeze.linalg.csvwrite(transDebugPath.toFile(), transMat)

  }

}