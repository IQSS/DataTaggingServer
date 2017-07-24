/**
 * Created by mor_vilozni on 24/07/2017.
 */
var Comments = (function(){
    function save(formID) {
        var uploadObj = {
            writer: $("#"+formID + " [data-role='name']").val(),
            comment : $("#"+formID + " [data-role='comment']").val(),
            versionPolicyModelID : $("#"+formID + " [data-role='versionPolicyModelID']").val(),
            version : $("#"+formID + " [data-role='version']").val(),
            targetType: $("#"+formID + " [data-role='targetType']").val(),
            targetContent: $("#"+formID + " [data-role='targetContent']").val(),
        };
        var call = jsRoutes.controllers.CommentsCtrl.apiAddComment();

        $.ajax(call.url, {
            type: "POST",
            data: JSON.stringify(uploadObj),
            dataType: "json",
            contentType: "application/json; charset=utf-8"
        }).done(function (data, status, jqXhr) {
            swal({
                title:"Comment added.",
                type:"success"
            });
        }).fail( function(jXHR, status, message){
            console.log(jXHR);
            console.log(status);
            console.log(message);
        });
    }
    function closeForm(formID) {
        console.log("Closing " + formID);
    }
    return {
        save: save,
        closeForm: closeForm
    };
})();