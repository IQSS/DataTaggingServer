package models

import play.api._
import java.nio.file._
import edu.harvard.iq.datatags.runtime._
import edu.harvard.iq.datatags.model.charts._
import edu.harvard.iq.datatags.parser.definitions.DataDefinitionParser
import edu.harvard.iq.datatags.parser.exceptions.DataTagsParseException
import edu.harvard.iq.datatags.parser.flowcharts._
import edu.harvard.iq.datatags.model.charts._
import edu.harvard.iq.datatags.model.types._

case class QuestionnaireKit( val id:String,
                             val title: String,
                             val tags: CompoundType,
                             val questionnaire: FlowChartSet )

object QuestionnaireKits {
  val allKits = loadQuestionnaires()
  
  /** This will go away once we have multi questionnaire support */
  val kit = allKits.toArray.apply(0)._2

  private def loadQuestionnaires() = {
    Logger.info("DataTags application started")
    Play.current.configuration.getString("datatags.folder") match {
    case Some(str) => {
          val p = Paths.get(str)
          Logger.info( "Loading questionnaire data from " + p.toAbsolutePath.toString )

          val dp = new DataDefinitionParser()
          val dataTags = dp.parseTagDefinitions( readAll(p.resolve("definitions.tags")), "definitions").asInstanceOf[CompoundType]
          val fcsParser = new FlowChartSetComplier( dataTags )

          val source = readAll( p.resolve("questionnaire.flow") )

          val interview = fcsParser.parse(source, "Data Deposit Screening" )
          Logger.info("Default chart id: %s".format(interview.getDefaultChartId) )
          
          Map( "dds-c1" -> QuestionnaireKit("dds-c1", "Data Deposit Screening", dataTags, interview) )
        }
        case None => {
          Logger.error("Bad configuration: Can't find \"datatags.folder\"")
          Map[String, QuestionnaireKit]()
        }
    }
  }

  private def readAll( p:Path ) : String = scala.io.Source.fromFile( p.toFile ).getLines().mkString("\n")
}