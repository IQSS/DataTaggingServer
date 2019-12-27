/* jshint esversion:6 */

var urlField = null;

function setup(){
    urlField = document.getElementById("fldAboutUrl");
    UiUtils.onEnter(urlField, save);
    UiUtils.onEnter(document.getElementById("fldServerName"), save);
    UiUtils.onEnter(document.getElementById("fldFooterText"), save);
    UiUtils.onEnter(document.getElementById("fldStatement"),  save);
    UiUtils.onEnter(document.getElementById("fldAboutLabel"), save);
}

function save() {
    if ( ! urlField.validity.valid ) {
        swal("Invalid Project URL","Project URL has to be a valid URL. Start with http:// or https://, and contain only valid URL characters.","error");
        urlField.focus();
        return;
    }
    const data = {};
    data.SERVER_NAME=document.getElementById("fldServerName").value;
    data.FOOTER_TEXT=document.getElementById("fldFooterText").value;
    data.STATEMENT_TEXT=document.getElementById("fldStatement").value;
    data.PROJECT_NAVBAR_URL=document.getElementById("fldAboutUrl").value;
    data.PROJECT_NAVBAR_TEXT=document.getElementById("fldAboutLabel").value;

    const call = jsRoutes.controllers.CustomizationCtrl.apiSetCustomizations();
    const msg = Informationals.showBackgroundProcess("Saving extra texts");
    $.ajax(call.url, {
        type: call.method,
        data: JSON.stringify(data),
        dataType: "text",
        contentType: "application/json; charset=utf-8"
    }).done(function (data, status, jqXhr) {
        msg.success();
        Informationals.makeSuccess( "Texts updated successfully", 2000 );
    }).fail( function(jXHR, status, message){
        msg.dismiss();
        Informationals.makeDanger("Error saving texts: ", message + " (" + status + ")" );
        console.log(jXHR);
        console.log(status);
        console.log(message);
    });
}

function openProjectUrl(){
    const url = urlField.value.trim();
    if ( url.length === 0 ) {
        swal({title: "Project URL is empty", icon:"error"});

    } else if ( urlField.validity.valid ) {
        window.open(url, "_blank");

    } else {
        swal("Invalid Project URL","Project URL has to be a valid URL. Start with http:// or https://, and contain only valid URL characters.","error");
    }

}