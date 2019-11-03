package org.clulab.aske.automates.apps


import java.io.{File, PrintWriter}

import ai.lum.common.ConfigUtils._
import ai.lum.common.FileUtils._
import com.typesafe.config.{Config, ConfigFactory}
import org.clulab.aske.automates.data.{DataLoader, TextRouter, TokenizedLatexDataLoader}
import org.clulab.aske.automates.alignment.{Aligner, VariableEditDistanceAligner}
import org.clulab.aske.automates.grfn.GrFNParser.{mkHypothesis, mkLinkElement}
import org.clulab.aske.automates.OdinEngine
import org.clulab.aske.automates.entities.GrFNEntityFinder
import org.clulab.aske.automates.grfn.GrFNParser
import org.clulab.odin.Mention
import org.clulab.utils.{DisplayUtils, FileUtils}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.io.Source

class AlignmentBaseline() {
  def process() {
    //getting configs and such (borrowed from ExtractAndAlign):

    val config: Config = ConfigFactory.load()
    //mathsymbols:
    val mathSymbols = Source.fromFile("/home/alexeeva/Repos/automates/text_reading/src/main/resources/AlignmentBaseline/mathSymbols.tsv").getLines().toArray.filter(_.length > 0).sortBy(_.length).reverse

//    for (ms <- mathSymbols) println(ms)

    val textConfig: Config = config[Config]("TextEngine")
    val textReader = OdinEngine.fromConfig(textConfig)
    val commentReader = OdinEngine.fromConfig(config[Config]("CommentEngine"))
    val inputDir = config[String]("apps.baslineInputDirectory")
    val inputType = config[String]("apps.inputType")
    val dataLoader = DataLoader.selectLoader(inputType) // txt, json (from science parse), pdf supported
    val files = FileUtils.findFiles(inputDir, dataLoader.extension)
    val eqFileDir = config[String]("apps.baselineEquationDir")

    //todo: this is just one equation; need to have things such that text files are read in parallel with the corresponding equation latexes

    //    for (eqCand <- allEqVarCandidates) println(eqCand)

//    val longestCand = allEqVarCandidates.sortBy(_.length).reverse.head
//    println("-->" + longestCand)




//    //todo: same as above---need to have this one file by one in parallel with the equation latex
//    val textMentions = files.par.flatMap { file =>
//      val textRouter = new TextRouter(Map(TextRouter.TEXT_ENGINE -> textReader, TextRouter.COMMENT_ENGINE -> commentReader))
//      val texts: Seq[String] = dataLoader.loadFile(file)
//      // Route text based on the amount of sentence punctuation and the # of numbers (too many numbers = non-prose from the paper)
//      texts.flatMap(text => textRouter.route(text).extractFromText(text, filename = Some(file.getName)))
//    }

    val file2FoundMatches = files.par.flatMap { file =>  //should return sth like Map[fileId, (equation candidate, text candidate) ]
      //for every file, get the text of the file
      val texts: Seq[String] = dataLoader.loadFile(file)
    //todo: change greek letter in mentions to full words
      //extract the mentions
      val textMentions = texts.flatMap(text => textReader.extractFromText(text, filename = Some(file.getName)))
      //only get the definition mentions
      val textDefinitionMentions = textMentions.seq.filter(_ matches "Definition")

      val equationName = eqFileDir.toString + file.toString.split("/").last.replace("json","txt")
      println(equationName + "<--")
      val equationStr = getEquationString(equationName)
      val allEqVarCandidates = getAllEqVarCandidates(equationStr)
      //get the name of the equation file
      //for now let's just use our old one

      val latexTextMatches = getLatexTextMatches(textDefinitionMentions, allEqVarCandidates, mathSymbols)

      latexTextMatches
    }

//    for (f2fm <- file2FoundMatches) println("==>" + f2fm)

    for (m <- file2FoundMatches) println(s"Matches: ${m._1}, ${m._2}")
  }

  def getLatexTextMatches(textDefinitionMentions: Seq[Mention], allEqVarCandidates: Seq[String], mathSymbols: Seq[String]): Seq[(String, String)] = {

    //for every extracted var-def var, find the best matching latex candidate var by iteratively replacing math symbols until the variables match up; out of those, return max length with matching curly brackets

    //all the matches from one file name will go here:
    val latexTextMatches = new ArrayBuffer[(String, String)]()
    //for every extracted mention
    for (m <- textDefinitionMentions) {
      //best candidates, out of which we'll take the max (to account for some font info
      val bestCandidates = new ArrayBuffer[String]()
      //for every candidate eq var
      for (cand <- allEqVarCandidates) {
        //check if the candidate matches the var extracted from text
        val resultOfMatching = findMatchingVar(m, cand, mathSymbols)
        //the result of matching for now is either the good candidate returned or the string "None"
        //todo: this None thing is not pretty---redo with an option or sth
        if (resultOfMatching != "None") {
          //println("-->" + "text mention: " + m.text + " " + findMatchingVar(m, cand, mathSymbols))
          //if the candidate is returned (instead of None), it's good and thus added to best candidates
          bestCandidates.append(resultOfMatching)
        }
      }

      //when did all the looping, choose the most complete (longest) out of the candidates and add it to the seq of matches for this file
      if (bestCandidates.nonEmpty) {
        latexTextMatches.append((bestCandidates.sortBy(_.length).reverse.head, m.text))
      }
    }
    latexTextMatches
  }

  def findMatchingVar(textMention: Mention, latexCandidateVar: String, mathSymbols: Seq[String]): String = {
    //only proceed if the latex candidate does not have unmatched braces
    if (!checkIfUnmatchedCurlyBraces(latexCandidateVar)) {
      //good candidates will go here
      val replacements = new ArrayBuffer[String]()
      replacements.append(latexCandidateVar)
      for (ms <- mathSymbols) {
        val pattern = if (ms.startsWith("\\")) "\\" + ms else ms
        //      println(pattern)

        val anotherReplacement = replacements.last.replaceAll(pattern, "")
        //      if (replacements.last.length != anotherReplacement.length) println("another replacement: " + anotherReplacement)
        replacements.append(anotherReplacement)
      }
      //
      val maxReplacement = replacements.last.replaceAll("\\{","").replaceAll("\\}","")
      //println(maxReplacement)
      val toReturn = if (maxReplacement == textMention.arguments("variable").head.text) latexCandidateVar else "None"
      //
      return toReturn
    }
    else "None"
  }


  def checkIfUnmatchedCurlyBraces(string: String): Boolean = {
    //from here: https://stackoverflow.com/questions/562606/regex-for-checking-if-a-string-has-mismatched-parentheses
    //if unmatched curly braces, return true
    var depth = 0
    for (ch <- string) if (depth >= 0) {
      ch match {
        case '{' => depth += 1
        case '}' => depth -= 1
        case _ => depth
      }
    }
    if (depth!=0) true else false
  }


  def getEquationString(latexFile: String): String = {
    val latexLines = Source.fromFile(latexFile).getLines().toArray
    val equationCandidates = for (
      i <- 0 to latexLines.length - 1
      if (latexLines(i).contains("begin{equation}"))

    ) yield latexLines(i+1).replaceAll("\\\\label\\{.*?\\}","")
    equationCandidates.head
  }

  def getAllEqVarCandidates(equation: String): Seq[String] = {
    //just getting all continuous partitions from the equation,
    //for now, even those containing mathematical symbols---bc we extract compound vars that can contain anything
    //maybe still get rid of the equal sign, integral, some types of font info
    val eqCandidates = new ArrayBuffer[String]()
    for (i <- 0 to equation.length) {
      for (j <- i + 1 until equation.length) {
        val eqCand = equation.slice(i,j)
        if (!eqCandidates.contains(eqCand)) eqCandidates.append(equation.slice(i,j))
      }
    }
    eqCandidates
  }



}

// store your static methods/fields in the "object" construct
object AlignmentBaseline {
  // this is just like main() in Java
  def main(args:Array[String]) {
    val fs = new AlignmentBaseline()//(args(0))
    fs.process()
  }
}
