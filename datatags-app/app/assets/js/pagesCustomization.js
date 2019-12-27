/* jshint esversion:6 */

var homeEditor = null;
var modelsEditor = null;
var aboutEditor = null;
const editors = {};
const NAMES = ["home", "models", "about"];
function customizationSetup() {
    const fullToolbarOptions = [
        ['bold', 'italic', 'underline', 'strike'],        // toggled buttons
        ['blockquote', 'code-block'],
        [{ 'header': [1, 2, 3, 4, 5, 6, false] }],

        [{ 'list': 'ordered'}, { 'list': 'bullet' }],
        [{ 'script': 'sub'}, { 'script': 'super' }],      // superscript/subscript
        [{ 'indent': '-1'}, { 'indent': '+1' }],          // outdent/indent
        [{ 'direction': 'rtl' }],                         // text direction

        [{ 'color': [] }, { 'background': [] }],          // dropdown with defaults from theme
        [{ 'font': [] }],
        [{ 'align': [] }],

        ['link','clean']                                         // remove formatting button
    ];

    NAMES.forEach(function(name){
       const ctrlId = "#" + name + "TextEditor";
       editors[name] = new Quill(ctrlId, {
           modules: { toolbar: fullToolbarOptions },
           theme: 'snow'
       });
    });

    loadContent();
}

function loadContent() {
    // GET http commands are easy, as there's no content in the request body
    $.ajax(jsRoutes.controllers.CustomizationCtrl.apiGetPageCustomizations()).done(
        function(data) {
            NAMES.forEach(function(name){
                const key = name.toUpperCase() + "_PAGE_TEXT";
                if ( data[key] ) {
                    editors[name].root.innerHTML = data[key];
                }
            });
        }
    );
}

function save(name) {
    const call = jsRoutes.controllers.CustomizationCtrl.apiSetCustomization(name.toLocaleUpperCase() + "_PAGE_TEXT");
    const msg = Informationals.showBackgroundProcess("Saving " + name);
    $.ajax(call.url, {
        type: call.method,
        data: editors[name].root.innerHTML,
        dataType: "text",
        contentType: "text/plain; charset=utf-8"
    }).done(function (data, status, jqXhr) {
        msg.success();
        Informationals.makeSuccess( name + " updated", 2000 );
    }).fail( function(jXHR, status, message){
        msg.dismiss();
        Informationals.makeDanger("Error saving customization data", message + " (" + status + ")" );
        console.log(jXHR);
        console.log(status);
        console.log(message);
    });
}
