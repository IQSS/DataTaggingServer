package views

import scala.xml.{Elem, PCData}
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import java.text.SimpleDateFormat
import java.util
import java.util.{Date, Optional}

import play.twirl.api.Html
import play.api.data.{Field, FormError}
import play.api.mvc.Request
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.{DataKey, MutableDataSet}
import edu.harvard.iq.datatags.externaltexts.{Localization, MarkupFormat, MarkupString}
import edu.harvard.iq.datatags.model.graphs.nodes.AskNode
import edu.harvard.iq.datatags.model.values.{AbstractValue, AggregateValue, AtomicValue, CompoundValue, ToDoValue}
import controllers.LoggedInAction
import models.{CommentingStatus, InterviewSession, Note, PublicationStatus}

object Helpers {
  
  private val MARKDOWN_OPTIONS_FULL = new MutableDataSet()
  MARKDOWN_OPTIONS_FULL.set(Parser.EXTENSIONS, util.Arrays.asList(
      TablesExtension.create(),
      StrikethroughExtension.create(),
      FootnoteExtension.create(),
      AutolinkExtension.create()
  ))
  MARKDOWN_OPTIONS_FULL.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  
  private val MARKDOWN_OPTIONS_MINIMAL = new MutableDataSet()
  MARKDOWN_OPTIONS_MINIMAL.set(Parser.EXTENSIONS, util.Arrays.asList(
    StrikethroughExtension.create()
  ))
  MARKDOWN_OPTIONS_MINIMAL.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  MARKDOWN_OPTIONS_MINIMAL.set(HtmlRenderer.DO_NOT_RENDER_LINKS.asInstanceOf[DataKey[Any]], true)
  MARKDOWN_OPTIONS_MINIMAL.set(HtmlRenderer.NO_P_TAGS_USE_BR.asInstanceOf[DataKey[Any]], true)
  
  private val TEXT_OPTIONS = new MutableDataSet()
  TEXT_OPTIONS.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  
  def hasContent(s: String) = (s != null) && s.trim.nonEmpty
  
  def hasContent( so:Option[String]) = so.nonEmpty && so.get.trim.nonEmpty
  
  def askNodeToMarkdown(n:AskNode) = {
    import scala.collection.JavaConverters._
    if ( n.getTermNames.isEmpty ) n.getText
    else {
      (Seq(n.getText, "##### Terms") ++
        n.getTermOrder.asScala.map( term => "* " + "*"+term+"*: " + n.getTermText(term))).mkString("\n\n")
    }
  }
  
  def render(markupString: MarkupString):Html = {
    markupString.getFormat match {
      case MarkupFormat.Markdown => renderMarkdown(markupString.getContent)
      
      case MarkupFormat.HTML => {
        // grab the part of the string between <body></body>
        val lcContent = markupString.getContent.toLowerCase
        val startIdx = lcContent.indexOf("<body>")
        if ( startIdx < 0 ) return Html(markupString.getContent)
        var endIdx = lcContent.indexOf("</body>", startIdx)
        if ( endIdx < 0 ) endIdx = lcContent.length
        Html( markupString.getContent.substring(startIdx+6,endIdx) )
      }
      
      case MarkupFormat.Text => {
        val parser = Parser.builder(TEXT_OPTIONS).build
        val renderer = HtmlRenderer.builder(TEXT_OPTIONS).build
        val document = parser.parse(markupString.getContent)
        Html(renderer.render(document))
      }
    }
  }
  
  def renderMinimalMarkdown(source:String ): Html = {
    val parser = Parser.builder(MARKDOWN_OPTIONS_MINIMAL).build
    val renderer = HtmlRenderer.builder(MARKDOWN_OPTIONS_MINIMAL).build
    val document = parser.parse(source.trim)
    val html = renderer.render(document).replaceAll("<br />","")
    Html(html)
  }
  
  def renderMarkdown( source:String ):Html = {
    val parser = Parser.builder(MARKDOWN_OPTIONS_FULL).build
    val renderer = HtmlRenderer.builder(MARKDOWN_OPTIONS_FULL).build
    val document = parser.parse(source)
    Html(renderer.render(document))
  }
  
  def renderMini( qNode:AskNode, loc:Localization ): Html = {
    val textOpt = loc.getNodeText(qNode.getId)
    if ( textOpt isPresent ) {
      val parser = Parser.builder(MARKDOWN_OPTIONS_FULL).build
      val renderer = HtmlRenderer.builder(MARKDOWN_OPTIONS_FULL).build
      val document = parser.parse(textOpt.get)
      val fullHtml = renderer.render(document)
      Html( fullHtml.replaceAll("<h[1-6]>","<strong>").replaceAll("</h[1-6]>","</strong><br>") )
      
    } else {
      bulletPoint(qNode.getText)
    }
  }
  
  def makeUpper(input: String) = {
    var returnable = ""
    if (isPhraseEnglishAndLower(input)) {
      var upper = ArrayBuffer[String]()
      val test = input.split(" ")
      
      for (i <- 0 until test.length) {
        if (isWordLowerEnglish(test(i))) {
          upper += test(i).capitalize
        } else {
          upper += test(i)
        }
      }
      returnable = upper.mkString(" ")
    } else {
      returnable = input
    }
    returnable
  }
  
  
  def isPhraseEnglishAndLower(input: String) = {
    var shouldBeChanged = false
    val test = input.split(" ")
    for (i <- 0 until test.length) {
      if (isWordLowerEnglish(test(i))) {
        shouldBeChanged = true
      }
    }
    shouldBeChanged
  }
  
  
  def isWordLowerEnglish(input: String):Boolean = input.filter( _.isLetter ).forall( _.isLower )
  
  def bulletPoint(paragraph: String) = {
    var complete = paragraph
    if (paragraph.contains("*")) {
      val split = paragraph.split("\n")
      
      val formatted = split.map(_.trim).map(line => if (line.startsWith("*")) "<li>" + line.drop(1) + "</li>"
      else "<p>%s</p>".format(line))
      
      val reformatted = formatted.tail.foldLeft(List(List(formatted.head)))((l, s) => {
        if (l.last.head.charAt(1) == s(1)) {
          l.dropRight(1) :+ (l.last :+ s)
        } else {
          l :+ List(s)
        }
      })
      
      val stringList = reformatted.map(group => if (group.head.contains("<li>")) "<ul>" + group.mkString + "</ul>" else group.mkString)
      
      complete = stringList.mkString
    }
    play.twirl.api.Html(complete)
  }
  
  def o2o[T]( in:Optional[T]):Option[T] = if ( in.isPresent ) Some(in.get) else None
  
  def fieldStatus(f:Field):String = if(f.hasErrors) "has-danger" else ""
  
  def jsEscape(s:String) = s.replaceAll("\"", "\\\"")
  
  val msg2eng = Map(
    "error.email" -> "Invalid email address",
    "error.minLength" -> "Field cannot be empty"
  )
  
  def messageToEng( fe:FormError ):String = msg2eng.getOrElse(fe.message,fe.message)
  
  def userPresent(req:Request[_]) = req.session.get(LoggedInAction.KEY).isDefined
  
  val publicationStatus2Str = Map(
    PublicationStatus.Private    -> "Logged-in users only",
    PublicationStatus.LinkOnly   -> "Logged-in users or users with a link",
    PublicationStatus.Published  -> "Everyone"  )
  
  val commentingStatus2Str = Map(
    CommentingStatus.Closed            -> "Nobody",
//    CommentingStatus.LoggedInUsersOnly -> "Logged-in users only",
    CommentingStatus.Everyone          -> "Everyone"
  )
  
  def dateTimeFormat( d:Date ):String = {
    val fmt = new SimpleDateFormat("yyyy-MM-dd hh:mm")
    fmt.format(d)
  }
  
  def nonEmpty( v:String )(f:String=>Html):Html = {
    if ( v != null && v.trim.nonEmpty ) {
      f(v.trim)
    } else {
      Html("")
    }
  }
  
  def ifNotEmpty(s:String)(block:String=>Html):Html = {
    if ( s!=null && s.trim.nonEmpty ) block(s) else Html("")
  }
  def ifNotEmpty(so:Option[String])(block:String=>Html):Html = so.map(s=>ifNotEmpty(s)(block)).getOrElse(Html(""))
  def ifNotEmpty[T]( col:TraversableOnce[T])(block:TraversableOnce[T]=>Html):Html = if(col!=null && col.nonEmpty) block(col) else Html("")
  
  
  def transcriptAsXml(session:InterviewSession, noteMap:Map[String,Note]):scala.xml.Node = {
    val head = <metadata>
          <model>
            <id>{session.kit.md.id.modelId}</id>
            <version>{session.kit.md.id.modelId}</version>
            <localization>{session.localization.getLanguage}</localization>
          </model>
        </metadata>
    
    val policyValue = <result>{policyValueAsXml(session.tags)}</result>

    val questionTextMap = session.answerHistory.map( ans =>
      ans.question.getId -> session.localization.getNodeText(ans.question.getId).orElse(Helpers.askNodeToMarkdown(ans.question))).toMap

    val answerMap = session.answerHistory.map( ans => (
      ans.question.getId,
      session.localization.localizeAnswer(ans.answer.getAnswerText)
    )
    ).toMap
    
    val body:Elem = <interview>
      {session.answerHistory.map( ans => <question id={ans.question.getId}>
        <text>{PCData(questionTextMap(ans.question.getId))}</text>{
        noteMap.get(ans.question.getId).map(_.note).map(txt=> <note>{PCData(txt)}</note>).getOrElse(scala.xml.Null)
        }<answer>{answerMap(ans.question.getId)}</answer>
      </question>)}
    </interview>
    
    scala.xml.Utility.trim(
      <transcript>
        {head}
        {policyValue}
        {body}
      </transcript>
    )
  }

  def vizNames = Map("decision-graph" -> "Decision Graph", "policy-space"-> "Policy Space")
  
  
  def policyValueAsXml( pv:AbstractValue ):scala.xml.Elem = {
    pv match {
      case at:AtomicValue    => <atomic slot={at.getSlot.getName} ordinal={at.getOrdinal.toString} outOf={at.getSlot.values().size().toString}>{at.getName}</atomic>
      case ag:AggregateValue => <aggreate slot={ag.getSlot.getName}>{ag.getValues.asScala.map(v=>v.getName).map( v => <value>{v}</value>)}</aggreate>
      case cm:CompoundValue  => <compound slot={cm.getSlot.getName}>
                                    {cm.getNonEmptySubSlots.asScala.map(cm.get).map( policyValueAsXml )}
                                </compound>
      case td:ToDoValue      => <todo slot={td.getSlot.getName} />
    }
  }

//  object tagsVisibility {
    def changeSet(id:String, set:Set[String]):Option[String] = {
      val ans = if (set.contains(id)) set - id else set + id
      if(ans.isEmpty) None else Some(ans.mkString(","))
    }
//  }

}