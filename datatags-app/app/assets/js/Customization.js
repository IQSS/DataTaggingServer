/* jshint esversion:6 */

/**
 * Created by michael on 20/7/17.
 */

let quill;

function customizationSetup() {
    const toolbarOptions = [
        ['bold', 'italic', 'underline', 'strike'],        // toggled buttons
        ['blockquote', 'code-block'],

        [{ 'header': 1 }, { 'header': 2 }],               // custom button values
        [{ 'list': 'ordered'}, { 'list': 'bullet' }],
        [{ 'script': 'sub'}, { 'script': 'super' }],      // superscript/subscript
        [{ 'indent': '-1'}, { 'indent': '+1' }],          // outdent/indent
        [{ 'direction': 'rtl' }],                         // text direction

        [{ 'header': [1, 2, 3, 4, 5, 6, false] }],

        [{ 'color': [] }, { 'background': [] }],          // dropdown with defaults from theme
        [{ 'font': [] }],
        [{ 'align': [] }],

        ['clean']                                         // remove formatting button
    ];

    quill = new Quill('#frontPageTextEditor', {
        modules: { toolbar: toolbarOptions },
        theme: 'snow'
    });

    loadContent();
}

function loadContent() {
    // GET http commands are easy, as there's no content in the request body
    $.ajax(jsRoutes.controllers.BackendCtrl.apiGetCustomizations()).done(
        function(data) {
            quill.root.innerHTML = data.frontPageText;
        }
    );
}

function save() {
    const uploadObj = {
        frontPageText: quill.root.innerHTML,
        parentProjectLink : "",
        parentProjectText : ""
    };

    // POST/PUT commands require body, so they're a bit more complicated
    const call = jsRoutes.controllers.BackendCtrl.apiSetCustomizations();

    const msg = Informationals.showBackgroundProcess("Saving...");
    $.ajax(call.url, {
        type: call.method,
        data: JSON.stringify(uploadObj),
        dataType: "json",
        contentType: "application/json; charset=utf-8"
    }).done(function (data, status, jqXhr) {
        msg.success();
        Informationals.makeSuccess("Customization saved", 1500 );
    }).fail( function(jXHR, status, message){
        msg.dismiss();
        Informationals.makeDanger("Error saving customization data", message + " (" + status + ")" );
        console.log(jXHR);
        console.log(status);
        console.log(message);
    });
}