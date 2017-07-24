package views


import java.util
import java.util.Optional

import com.vladsch.flexmark.ext.footnotes.FootnoteExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.options.MutableDataSet
import edu.harvard.iq.datatags.externaltexts.{Localization, MarkupFormat, MarkupString}

import scala.collection.mutable.ArrayBuffer
import play.api.templates._
import play.twirl.api.Html
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import controllers.LoggedInAction
import edu.harvard.iq.datatags.model.graphs.nodes.AskNode
import edu.harvard.iq.datatags.model.values.TagValue
import models.{CommentingStatus, PublicationStatus}
import play.api.data.{Field, FormError}
import play.api.mvc.Request

object Helpers {
  
  private val MARKDOWN_OPTIONS_FULL = new MutableDataSet()
  MARKDOWN_OPTIONS_FULL.set(Parser.EXTENSIONS, util.Arrays.asList(
      TablesExtension.create(),
      StrikethroughExtension.create(),
      FootnoteExtension.create()
  ))
  MARKDOWN_OPTIONS_FULL.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  
  private val MARKDOWN_OPTIONS_MINIMAL = new MutableDataSet()
  MARKDOWN_OPTIONS_MINIMAL.set(Parser.EXTENSIONS, util.Arrays.asList(
    StrikethroughExtension.create()
  ))
  MARKDOWN_OPTIONS_MINIMAL.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  
  private val TEXT_OPTIONS = new MutableDataSet()
  TEXT_OPTIONS.set(HtmlRenderer.SOFT_BREAK, "<br />\n")
  
  def hasContent(s: String) = (s != null) && s.trim.nonEmpty
  
  def hasContent( so:Option[String]) = so.nonEmpty && so.get.trim.nonEmpty
  
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
    val document = parser.parse(source)
    Html(renderer.render(document))
  }
  
  def renderMarkdown( source:String ):Html = {
    val parser = Parser.builder(MARKDOWN_OPTIONS_FULL).build
    val renderer = HtmlRenderer.builder(MARKDOWN_OPTIONS_FULL).build
    val document = parser.parse(source)
    Html(renderer.render(document))
  }
  
  def renderMini( qNode:AskNode, loc:Option[Localization] ): Html = {
    val textOpt = loc.map(l=>l.getNodeText(qNode.getId)).getOrElse(java.util.Optional.empty)
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
  
  
  def isWordLowerEnglish(input: String) = {
    val isPunctuation = input.endsWith("?") || input.endsWith("!") || input.endsWith(".")
    val isLastLowerLetter = input.charAt(input.length - 1).isLetter && !input.charAt(input.length - 1).isUpper
    
    var isLower = true
    for (i <- 0 to input.length - 2) {
      if (!input.charAt(i).isLetter || input.charAt(i).isUpper) {
        isLower = false
      }
    }
    if (!isLastLowerLetter && !isPunctuation) {
      isLower = false
    }
    isLower
  }
  
  def bulletPoint(paragraph: String) = {
    var complete = paragraph
    if (paragraph.contains("*")) {
      val split = paragraph.split("\n")
      
      val formatted = split.map(_.trim).map(line => if (line.startsWith("*")) "<li>" + line.drop(1) + "</li>"
      else "<p>%s</p>".format(line))
      
      val reformatted = formatted.tail.foldLeft(List(List(formatted.head)))((l, s) => {
        if (l.last.head(1) == s(1)) {
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
  
  def fieldStatus(f:Field):String = if(f.hasErrors) "has-error" else ""
  
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
  
}