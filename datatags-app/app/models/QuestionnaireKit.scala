package models

import play.api._
import java.nio.file._
import javax.inject.{Inject, Singleton}
import scala.collection.JavaConverters._

import edu.harvard.iq.datatags.model.graphs._
import edu.harvard.iq.datatags.model.types.CompoundSlot
import edu.harvard.iq.datatags.parser.decisiongraph.DecisionGraphParser
import edu.harvard.iq.datatags.parser.tagspace.TagSpaceParser

case class QuestionnaireKit(id:String,
                            title: String,
                            tags: CompoundSlot,
                            graph: DecisionGraph,
                            messages:Map[String, String],
                            readme:Option[String]) {
  val serializer = Serialization( graph, tags )
}

@Singleton
class QuestionnaireKits @Inject() (config:Configuration){
  val allKits: Map[String,QuestionnaireKit] = loadModels()
  
  def get(id:String):Option[QuestionnaireKit] = allKits.get(id)
  
  private def loadModels() = {
    Logger.info("Loading models")
    config.get[Option[String]]("taggingServer.models.folder") match {
    case Some(str) => {
          val p = Paths.get(str)
          Logger.info( "Loading questionnaire data from " + p.toAbsolutePath.toString )
          Files.list(p).iterator().asScala
            .filter( Files.isDirectory(_) )
            .map( f => (f.getFileName.toString, loadSingleKit(f)) )
            .toMap
        }

        case None => {
          Logger.error("Bad configuration: Can't find \"datatags.folder\"")
          Map[String, QuestionnaireKit]()
        }
    }
  }
  
  private def loadSingleKit( p:Path ):QuestionnaireKit = {
    val definitions = p.resolve("definitions.ts")
    Logger.info( "Reading definitions from %s".format(definitions.toAbsolutePath.toString))
    val dp = new TagSpaceParser()
    val dataTags = dp.parse( readAll(definitions) ).buildType("DataTags").get
    Logger.info(" DataTags type: " + dataTags.toString )
    Logger.info( " - DONE")
  
    val fcsParser = new DecisionGraphParser()
  
    val questionnaire = p.resolve("questionnaire.dg")
    Logger.info( "Reading questionnaire from %s".format(questionnaire.toAbsolutePath.toString))
    val source = readAll( questionnaire )
    Logger.info( " - READ DONE")
    val interview = fcsParser.parse(source).compile(dataTags)
    Logger.info( " - PARSING DONE")
  
    Logger.info("Loading messages")
    val messagesFile = p.resolve("messages.properties")
    val messages = readAllToSeq( messagesFile ).filter(_.nonEmpty).map(_.split("=",2)).map(arr=>(arr(0),arr(1))).toMap
  
    Logger.info("Loading readme")
    val readmeRaw = readAll(p.resolve("README.html"))
    val readMe = "(?s)<body>.*</body>".r.findFirstIn(readmeRaw).map( s=>s.substring("<body>".length, s.length-"</body>".length).trim )
    
    QuestionnaireKit(p.getFileName.toString, messages.getOrElse("title", p.getFileName.toString), dataTags, interview, messages, readMe)
  }
  
  private def readAllToItr( p:Path ) : Iterator[String] = scala.io.Source.fromFile( p.toFile, "utf-8" ).getLines()
  private def readAll( p:Path ) : String = readAllToItr(p).mkString("\n")
  private def readAllToSeq( p:Path ) : Seq[String] = readAllToItr(p).toSeq


  private def matchNode(aNode: nodes.Node, answerFrequencies: scala.collection.mutable.Map[Answer, Integer]): scala.collection.mutable.Map[Answer, Integer] = aNode match {

    case n:nodes.AskNode => { // if node is AskNode, update frequency of answer
      val answerItr = n.getAnswers.iterator
      while (answerItr.hasNext) { // while answers remain in the node
        val nextAns = answerItr.next
        if (answerFrequencies.contains(nextAns)) { // if answer is already listed, update number
          val frequency = answerFrequencies(nextAns)
          answerFrequencies.update(nextAns, frequency+1)
        } else { // if not, insert answer
          answerFrequencies.put(nextAns,1)
        }
      }
      answerFrequencies
     }

    case _ => { // if node is any other node, simply return the current answer frequency map
      answerFrequencies
    }
  }

}


