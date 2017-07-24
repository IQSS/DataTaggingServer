/**
 *
 * Controller for adding comments.
 *
 * Created by mor_vilozni on 24/07/2017.
 */
var Comments = (function(){
    function save(formID) {
        var uploadObj = {
            writer: $("#"+formID + " [data-role='name']").val(),
            comment : $("#"+formID + " [data-role='comment']").val(),
            versionedPolicyModelID : $("#"+formID + " [data-role='versionPolicyModelID']").val(),
            version : Number($("#"+formID + " [data-role='version']").val()),
            targetType: $("#"+formID + " [data-role='targetType']").val(),
            targetContent: $("#"+formID + " [data-role='targetContent']").val()
        };

        var loc = $("#"+formID + " [data-role='localization']").val();
        uploadObj.loc = loc;

        var call = jsRoutes.controllers.CommentsCtrl.apiAddComment();

        $.ajax(call.url, {
            type: call.type,
            data: JSON.stringify(uploadObj),
            dataType: "json",
            contentType: "application/json; charset=utf-8"

        }).done(function (data, status, jqXhr) {
            $("#"+formID + " [data-role='comment']").val("");
            swal({
                title:"Comment added.",
                text:"Thank you!",
                type:"success",
                timer: 2500
            });

        }).fail( function(jXHR, status, message){
            console.log(jXHR);
            console.log(status);
            console.log(message);

        }).always( function(){
            closeForm(formID);

        });
    }

    function closeForm(formID) {
        $("#"+formID).slideUp();
    }

    return {
        save: save,
        closeForm: closeForm
    };
})();