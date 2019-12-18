/*jshint esversion: 6 */
var ModelEditor = (function(){
    function deleteFile(id) {
        $.ajax( jsRoutes.controllers.ModelCtrl.apiDoDeleteModel(id) )
            .done( function(data){
               if ( data.result ) {
                window.location.href=jsRoutes.controllers.ModelCtrl.showModelsList().url;
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
                title: "Delete Model '"+name+"'?",
                text: "This operation cannot be undone. External links referring to this model will be broken.",
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
                        text: "Delete Model",
                        value: true,
                        visible: true,
                        className: "",
                        closeModal: false
                    }
                }
            }).then(function(isConfirm) {
                if (isConfirm) {
                    deleteFile(id);
                } else {
                    swal.close();
                }
            });
        }
    };
})();

function changeInput(name, position) {
    console.log("name", name);
    console.log("pos", position);
    // $("[name=slt-" + name + "]")[0].value = position;
    $("#slt-"+name).val(position);
}