/**
 * Created by michael on 20/7/17.
 */

function customizationSetup() {
    pell.init({
            // <HTMLElement>, required
            element: document.getElementById('pell'),

            // <Function>, required
            // Use the output html, triggered by element's `oninput` event
            onChange: function(){},

            // <boolean>, optional, default = false
            // Outputs <span style="font-weight: bold;"></span> instead of <b></b>
            styleWithCSS: false,

            // <Array[string | Object]>, string if overwriting, object if customizing/creating
            // action.name<string> (only required if overwriting)
            // action.icon<string> (optional if overwriting, required if custom action)
            // action.title<string> (optional)
            // action.result<Function> (required)
            // Specify the actions you specifically want (in order)
            actions: [
                'bold',
                'italic',
                'underline',
                'strikethrough',
                'heading1',
                'heading2',
                'paragraph',
                'quote',
                'olist',
                'ulist',
                'code',
                'line',
                'link',
                'image'
            ],

            // classes<Array[string]> (optional)
            // Choose your custom class names
            classes: {
                actionbar: 'pell-actionbar',
                    button: 'pell-button',
                    content: 'pell-content'
            }
        });

    loadContent();
}

function loadContent() {
    // GET http commands are easy, as there's no content in the request body
    $.ajax(jsRoutes.controllers.BackendCtrl.apiGetCustomizations()).done(
        function(data) {
            $("#pell .pell-content").html(data.frontPageText);
            console.log("Data loaded");
        }
    );
}

function save() {
    var uploadObj = {
        frontPageText: $("#pell .pell-content").html(),
        parentProjectLink : "",
        parentProjectText : ""
    };

    // POST/PUT commands require body, so they're a bit more complicated
    var call = jsRoutes.controllers.BackendCtrl.apiSetCustomizations();

    $.ajax(call.url, {
        type: "POST",
        data: JSON.stringify(uploadObj),
        dataType: "json",
        contentType: "application/json; charset=utf-8"
    }).done(function (data, status, jqXhr) {
        swal({
            title:"Text Updated.",
            type:"success"
        });
    }).fail( function(jXHR, status, message){
        console.log(jXHR);
        console.log(status);
        console.log(message);
    });
}