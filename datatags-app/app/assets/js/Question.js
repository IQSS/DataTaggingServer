var Question = function() {
  var router = jsRoutes.controllers.Interview;
  return {
    revisit: function( questionnaireId, nodeId, answerText ) {
      var req = router.answer(questionnaireId, nodeId);
      req.data = {"answerText" : answerText };

      console.log("Sending a " + req.method + " request to " + req.absoluteURL() + " with data " + JSON.stringify(req.data) );

      $.ajax( req )
       .success( function( data, textStatus, jqXhr ){
          console.log("Success!");
          console.log( JSON.stringify({data:data, textStatus:textStatus, jqXhr:jqXhr}));})
       .fail( function(xhr, status, error) {
          console.log("Error: " + error + " (status: " + status + ")" );} );
    },

    startOver: function( questionnaireId ) {
      window.location = router.showStartInterview(questionnaireId).absoluteURL();
    }




};}();

var qUP   = "fa-chevron-circle-up";
var qDOWN = "fa-chevron-circle-down";

function toggleCard(button, divId) {
    $("#" + divId).slideToggle();
    var iconEmt = button.getElementsByTagName("i")[0];
    if ( iconEmt.className.indexOf(qUP) >= 0 ) {
        iconEmt.classList.remove(qUP);
        iconEmt.classList.add(qDOWN);
    } else {
        iconEmt.classList.remove(qDOWN);
        iconEmt.classList.add(qUP);
    }
}
