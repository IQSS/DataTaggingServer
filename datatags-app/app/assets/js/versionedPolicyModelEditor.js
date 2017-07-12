var VpmEditor = (function(){
    function deleteFile(id) {
        $.ajax( jsRoutes.controllers.PolicyKitManagementCtrl.apiDoDeleteVpm(id) )
            .done( function(data){
               if ( data.result ) {
                window.location.href=jsRoutes.controllers.PolicyKitManagementCtrl.showVpmList().url;
               } else {
                   swal({
                       title:"Error during deletion",
                       type:"error",
                       text:"The model might have already been deleted"
                   });
               }
            });
    }

    return {
        confirmDelete: function(name, id) {
            swal({
                    title: "Delete Versioned policy model '"+name+"'?",
                    text: "This operation cannot be undone. External links referring to this model will be broken.",
                    type: "warning",
                    showCancelButton: true,
                    confirmButtonText: "Yes, delete it",
                    cancelButtonText: "Cancel",
                    closeOnConfirm: false,
                    closeOnCancel: true
                },
                function(isConfirm){
                    if (isConfirm) {
                        deleteFile(id);
                    }
                });
        }
    };
})();
