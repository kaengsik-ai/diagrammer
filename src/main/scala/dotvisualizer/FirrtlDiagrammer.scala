/*
Copyright 2020 The Regents of the University of California (Regents)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package dotvisualizer

import java.io.{File, PrintWriter}

import chisel3.experimental.ChiselAnnotation
import dotvisualizer.transforms.{MakeDiagramGroup, ModuleLevelDiagrammer}
import firrtl._
import firrtl.options.TargetDirAnnotation
import firrtl.annotations._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process._

//scalastyle:off magic.number
//scalastyle:off regex

/**
  * This library implements a graphviz dot file render.
  */
trait OptionAnnotation extends NoTargetAnnotation with ChiselAnnotation {
  override def toFirrtl: Annotation = {
    this
  }
}

case class StartModule(startModule: String) extends OptionAnnotation

case class SetRenderProgram(renderProgram: String = "dot") extends OptionAnnotation

case class SetOpenProgram(openProgram: String) extends OptionAnnotation

case class DotTimeOut(seconds: Int) extends OptionAnnotation

case class RankDirAnnotation(rankDir: String) extends OptionAnnotation

case object UseRankAnnotation extends OptionAnnotation

case object ShowPrintfsAnnotation extends OptionAnnotation

object FirrtlDiagrammer {

  var dotTimeOut = 7

  /**
    * get the target directory from the annotations
    * Do a bit of work to try and avoid inadvertent reference to /
    * @param annotationSeq annotations to search
    * @return
    */
  def getTargetDir(annotationSeq: AnnotationSeq): String = {
    val targetDir = annotationSeq.collectFirst { case x : TargetDirAnnotation => x } match {
      case Some(TargetDirAnnotation(value)) if value.nonEmpty =>
        if(value.endsWith("/")) value else value + "/"
      case _ => "./"
    }
    FileUtils.makeDirectory(targetDir)
    targetDir
  }

  /**
    * Open an svg file using the open program
    * @param fileName    file to be opened
    * @param openProgram program to use
    */
  def show(fileName: String, openProgram: String): Unit = {
    if(openProgram.nonEmpty && openProgram != "none") {
      val openProcessString = s"$openProgram $fileName.svg"
      openProcessString.!!
    }
    else {
      println(s"""There is no program identified which will render the svg files.""")
      println(s"""The file to start with is $fileName.svg, open it in the appropriate viewer""")
      println(s"""Specific module views should be in the same directory as $fileName.svg""")
    }
  }

  //scalastyle:off method.length
  def render(fileName: String, dotProgram: String = "dot"): Unit = {
    if(dotProgram != "none") {
      //noinspection SpellCheckingInspection
      if(fileName.isEmpty) {
        println(s"Tried to call render program $dotProgram without a filename")
      }
      else if(! new File(fileName).exists() ) {
        println(s"Tried to call render program $dotProgram on non existent file $fileName")
      }
      else {
        val dotProcessString = s"$dotProgram -Tsvg -O $fileName"
        val process = Process(dotProcessString).run()
        val processFuture = Future(blocking(process.exitValue()))
        try {
          Await.result(processFuture, Duration(dotTimeOut, "sec"))
        }
        catch {
          case _: TimeoutException =>
            println(s"Rendering timed out after $dotTimeOut seconds on $fileName with command $dotProcessString")
            println(s"You can try increasing with the --dot-timeout seconds flag")
            process.destroy()
            val printWriter = new PrintWriter(new File(fileName + ".svg"))
            printWriter.print(
              s"""
                |<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN"
                | "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
                |<!-- Generated by graphviz version 2.40.1 (20161225.0304)
                | -->
                |<!-- Title: TopLevel Pages: 1 -->
                |<svg width="351pt" height="44pt"
                | viewBox="0.00 0.00 351.26 44.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                |<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 40)">
                |<title>TopLevel</title>
                |<polygon fill="#ffffff" stroke="transparent" points="-4,4 -4,-40 347.2637,-40 347.2637,4 -4,4"/>
                |<!-- Sorry, Rendering timed out on this file, Use Back to return -->
                |<g id="node1" class="node">
                |<title>
                |Sorry, Rendering timed out on this file, Use Back to return.
                |You can try increasing the timeout with the --dot-timeout seconds flag
                |</title>
                |<polygon fill="none" stroke="#000000" points="343.3956,-36 -.1319,-36 -.1319,0 343.3956,0 343.3956,-36"/>
                |<text text-anchor="middle" x="171.6318" y="-13.8" font-family="Times,serif" font-size="14.00" fill="#000000">Sorry, Rendering timed out on this file, Use Back to return</text>
                |</g>
                |</g>
                |</svg>
              """.stripMargin)
            printWriter.close()

        }
      }
    }
  }

  /**
    * Make a simple css file that controls highlighting
    * @param targetDir where to put the css
    */
  def addCss(targetDir: String): Unit = {
    val file = new File(s"$targetDir/styles.css")
    val printWriter = new PrintWriter(file)
    printWriter.println(
      """
        |.edge:hover * {
        |  stroke: #ff0000;
        |}
        |.edge:hover polygon {
        |  fill: #ff0000;
        |}
      """.stripMargin
    )
    printWriter.close()
  }

  def run(config: Config): Unit = {
    val sourceFirrtl = {
      if(config.firrtlSource.nonEmpty) {
        config.firrtlSource
      }
      else {
       FileUtils.getText(config.firrtlSourceFile)
      }
    }

    dotTimeOut = config.dotTimeOut

    val ast = Parser.parse(sourceFirrtl)
    val controlAnnotations = config.toAnnotations

    val loweredAst = ToLoFirrtl.lower(ast)

    val targetDir = getTargetDir(controlAnnotations)
    FileUtils.makeDirectory(targetDir)

    addCss(targetDir)

    val circuitState = CircuitState(loweredAst, LowForm, controlAnnotations)

    if(config.justTopLevel) {
      val justTopLevelTransform = new ModuleLevelDiagrammer
      justTopLevelTransform.execute(circuitState)
    }
    else {
      val transform = new MakeDiagramGroup
      transform.execute(circuitState)
    }

    val fileName = s"$targetDir${circuitState.circuit.main}_hierarchy.dot"
    val openProgram = controlAnnotations.collectFirst {
      case SetOpenProgram(program) => program
    }.getOrElse("open")

    FirrtlDiagrammer.show(fileName, openProgram)
  }

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Config]("firrtl-diagrammer") {
      head("firrtl-diagrammer", "1.x")

      opt[String]('i', "firrtl-source")
                      .action { (x, c) => c.copy(firrtlSourceFile = x) }
                      .required()
                      .text("must be a valid text file containing firrtl")

      opt[String]('m', "module-name")
              .action { (x, c) => c.copy(startModuleName = x) }
              .text("the module in the hierarchy to start, default is the circuit top")

      opt[String]('t', "target-dir")
              .action { (x, c) => c.copy(targetDir = x) }
              .text("The name of the directory to put dot and svg files")

      opt[String]('o', "open-command")
              .action { (x, c) => c.copy(openProgram = x) }
              .text("The name of the program to open svg file in browser")

      opt[Unit]('j', "just-top-level")
              .action { (_, c) => c.copy(justTopLevel = true) }
              .text("use this to only see the top level view")

      opt[String]('d', "rank-dir")
              .action { (x, c) => c.copy(rankDir = x) }
              .text("use to set ranking direction, default is LR, TB is good alternative")

      opt[Unit]('r', "rank-elements")
              .action { (_, c) => c.copy(useRanking = true) }
              .text("tries to rank elements by depth from inputs")

      opt[Unit]('p', "show-printfs")
              .action { (_, c) => c.copy(showPrintfs = true) }
              .text("render printfs showing arguments")

      opt[Int]('s', "dot-timeout-seconds")
              .action { (x, c) => c.copy(dotTimeOut = x) }
              .text("gives up trying to diagram a module after 7 seconds, this increases that time")
    }

    parser.parse(args, Config()) match {
      case Some(config: Config) => run(config)
      case _ =>
        // bad arguments
    }
  }
}

case class Config(
  firrtlSourceFile: String = "",
  firrtlSource:     String = "",
  startModuleName:  String = "",
  renderProgram:    String = "dot",
  openProgram:      String = Config.getOpenForOs,
  targetDir:        String = "",
  justTopLevel:     Boolean = false,
  dotTimeOut:       Int     = 7,
  useRanking:       Boolean = false,
  rankDir:          String  = "LR",
  showPrintfs:      Boolean = false
) {
  def toAnnotations: Seq[Annotation] = {
    val dir = {
      val baseDir = if(targetDir.isEmpty) {
        firrtlSourceFile.split("/").dropRight(1).mkString("/")
      }
      else {
        targetDir
      }
      if(baseDir.isEmpty) { "./" }
      else if(baseDir.endsWith("/")) { baseDir }
      else { baseDir + "/" }
    }
    Seq(
      SetRenderProgram(renderProgram),
      SetOpenProgram(openProgram),
      TargetDirAnnotation(dir),
      DotTimeOut(dotTimeOut),
      RankDirAnnotation(rankDir)
    ) ++
    (if(startModuleName.nonEmpty) Seq(StartModule(startModuleName)) else Seq.empty) ++
    (if(useRanking) Seq(UseRankAnnotation) else Seq.empty) ++
    (if(showPrintfs) Seq(ShowPrintfsAnnotation) else Seq.empty)
  }
}

object Config {
  private val MacPattern = """.*mac.*""".r
  private val LinuxPattern = """.*n[iu]x.*""".r
  private val WindowsPattern = """.*win.*""".r

  def getOpenForOs: String = {
    System.getProperty("os.name").toLowerCase match {
      case MacPattern()     => "open"
      case LinuxPattern()   => "xdg-open"
      case WindowsPattern() => ""          // no clear agreement here.
      case _                => ""          // punt
    }
  }
}

