package models

import java.sql.Timestamp

/**
  * Models aggregate PolicyModel versions (using VersionKit).
  */
case class Model(id:String, title:String, created:Timestamp, note:String,
                 /** Save statistics about interviews (not users!) */
                 saveStat:Boolean,
                 
                 /** allow users to add notes to the interview */
                 notesAllowed:Boolean,
                 
                 /** Require users to go through an affirmation screen */
                 requireAffirmationScreen:Boolean,
                 
                 /** Allow users to view the interview using trivial localization */
                 displayTrivialLocalization:Boolean
                )
