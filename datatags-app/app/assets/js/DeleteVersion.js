var DeleteVersion = (function(){
    function deleteVersion(modelId, version) {
        swal({
            title: "Are you sure?",
            text:  "You will not be able to recover this version.",
            dangerMode: true,
            buttons:{
                cancel: {
                    text: "Cancel",
                    value: null,
                    visible: true,
                    className: "",
                    closeModal: true
                },
                confirm: {
                    text: "OK",
                    value: true,
                    visible: true,
                    className: "",
                    closeModal: false
                }
            }
        }).then(function(isConfirm){
            if (isConfirm) {
                var call = jsRoutes.controllers.ModelCtrl.deleteVersion(modelId, version);
                $.ajax(call.url, {
                    type: call.type,
                    contentType: "application/json; charset=utf-8"
                }).done(function (data, status, jqXhr) {
                    window.location = jsRoutes.controllers.ModelCtrl.showModelPage(modelId).url;
                });
            } else {
                swal.close();
            }
        });
    }
    return {
        deleteVersion: deleteVersion
    };
})();