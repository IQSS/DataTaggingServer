package controllers

import models.Comment

/**
  * A `Comment` data nugget.
  */
case class CommentDN( comment:Comment, modelName:String, versionTitle:String)