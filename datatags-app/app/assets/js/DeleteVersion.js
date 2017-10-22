var DeleteVersion = (function(){
    function deleteVersion(modelId, version) {
        swal({
                title: "Are you sure?",
                text: "You will not be able to recover this version!",
                type: "warning",
                showCancelButton: true,
                confirmButtonClass: "btn-danger",
                confirmButtonText: "Delete Version",
                cancelButtonText: "Cancel",
                closeOnConfirm: false,
                closeOnCancel: false
            },
            function(isConfirm) {
                if (isConfirm) {
                    var call = jsRoutes.controllers.PolicyKitManagementCtrl.deleteVersion(modelId, version);
                    $.ajax(call.url, {
                        type: call.type,
                        contentType: "application/json; charset=utf-8"
                    }).done(function (data, status, jqXhr) {
                        window.location = jsRoutes.controllers.PolicyKitManagementCtrl.showVpmPage(modelId).url;
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