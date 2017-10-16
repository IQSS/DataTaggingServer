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
        uploadObj.localization = loc;

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

    function toggleForm(formID) {
        var ctrl = $("#" + formID +" [data-role='name']");
        if ( ctrl.is(":visible") ) {
            closeForm(formID);

        } else {
            $("#"+formID).slideDown(
                {
                    complete: function(){
                        $("#" + formID + " [data-role='name']").focus();
                    }
                }
            );
        }
    }

    function setCommentResolved( cmtId, isResolved, callback ) {
        var call = jsRoutes.controllers.CommentsCtrl.apiSetCommentStatus(cmtId);
        $.ajax(call.url, {
            type: call.type,
            data: JSON.stringify(isResolved?"resolved":"not-resolved"),
            dataType: "json",
            contentType: "application/json; charset=utf-8"

        }).done(function (data, status, jqXhr) {
            if ( callback ) {
                callback();
            }
        });
    }

    function setResolved( btn, labelId, cmtId, isResolved ) {
        $(btn).html("<i class='fa fa-spin fa-cog'></i>");
        $(btn).attr("disabled", "disabled");
        setCommentResolved(cmtId, isResolved, function(){
            $(btn).html("Mark as " + (isResolved?"Unresolved":"Resolved") );
            $(btn).removeAttr("disabled");
            btn.onclick=function(){ setResolved(btn, labelId, cmtId, !isResolved); };

            if ( labelId ) {
                var classes = ["label-primary", "label-default"];
                var $label = $("#" + labelId);
                $label.removeClass(classes[isResolved?0:1]);
                $label.addClass(classes[isResolved?1:0]);
                $label.text(isResolved?"resolved":"open");
            }
        });
    }

    function deleteComment( cmtId, modelId, version ){
        swal({
            title: "Are you sure?",
            text: "You will not be able to recover this comment!",
            type: "warning",
            showCancelButton: true,
            confirmButtonClass: "btn-danger",
            confirmButtonText: "Yes, delete it!",
            cancelButtonText: "No, cancel!",
            closeOnConfirm: false,
            closeOnCancel: false
        },
        function(isConfirm) {
            if (isConfirm) {
                var call = jsRoutes.controllers.CommentsCtrl.deleteComment(cmtId);
                $.ajax(call.url, {
                    type: call.type,
                    contentType: "application/json; charset=utf-8"
                }).done(function (data, status, jqXhr) {
                    window.location = jsRoutes.controllers.PolicyKitManagementCtrl.showVersionPage(modelId, version).url;
                });
            } else {
                swal.close();
            }
        });
    }

    return {
        save: save,
        closeForm: closeForm,
        toggleForm: toggleForm,
        setResolved: setResolved,
        deleteComment: deleteComment
    };
})();