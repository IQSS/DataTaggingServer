/* jshint esversion:6 */

function colorInputs() {
    const elems = document.getElementsByTagName("input");
    const retVal = [];
    Object.keys(elems).forEach( function(i){
       const elem = elems[i];
       if (elem.type === "color") {
           retVal.push(elem);
       }
    });

    return retVal;
}

function collectCss() {
    // these correspond to the 3 css levels: selector, attribute, and value.
    const valueTriplets = colorInputs().map( function(inp){
        return [inp.dataset.selector, inp.dataset.key, inp.value];
    });

    // Now create an object that stores the CSS data
    const cssObj = {};
    valueTriplets.forEach( function(t){
        if ( !cssObj[t[0]] ) {
            cssObj[t[0]] = {};
        }
        cssObj[t[0]][t[1]]=t[2];
    });

    const cssCodeLines = [];
    Object.keys(cssObj).forEach( function(k){
        cssCodeLines.push( k + " { ");
        cssCodeLines.push( cssStringifyAttributes(cssObj[k]) );
        cssCodeLines.push( "}" );
    });

    cssCodeLines.push("/*---*/");
    cssCodeLines.push( document.getElementById("extraCss").value );

    return cssCodeLines.join("\n");
}

function cssStringifyAttributes( obj ) {
    const arr = [];
    Object.keys(obj).forEach(function(k){
        const val = obj[k];
        arr.push( "  " + k + ": " + val + ";" );
    });

    return arr.join("\n");
}

function setColor( selector, key, value ) {
    $("input[type='color']"
    ).filter(function(i,c){
        const data = $(c).data();
        return data.selector === selector && data.key===key;
    }).val(value);
}

function uploadLogo() {
    const files = document.getElementById("logoFile").files;
    const formData = new FormData();

    if ( files.length === 0 ) {
        Informationals.makeDanger("No file selected", "Please select an image file to use as a logo", 2000).show();
        return;
    }

    for (let i = 0; i < files.length; i++) {
        let file = files[i];

        formData.append('logo', file);
    }

    const msg = Informationals.showBackgroundProcess("Uploading");
    fetch(jsRoutes.controllers.CustomizationCtrl.apiSetLogo().url, {
        method: 'POST',
        body: formData,
    }).then(response => {
        if( response.status === 200 ) {
            msg.success();
            setHasImage(true);
            return null;
        } else {
            msg.dismiss();
            return response.json();
        }
    }).then( json => {
        if ( json ) {
            Informationals.makeDanger("Error uploading logo", json.message, 3000).show();
        }
    });
}

function deleteLogo() {
    swal({
        title: "Are you sure you want to delete the server's logo?",
        text: "This operation cannot be undone.",
        icon: "warning",
        buttons: {
            cancel:"cancel",
            confirm:"delete logo"
        },
        dangerMode: true
    }).then(function(willDelete){
        if (willDelete) {
            const msg = Informationals.showBackgroundProcess("Deleting logo");
            const call = jsRoutes.controllers.CustomizationCtrl.apiDeleteLogo();
            fetch( call.url, {
                method: call.method
            }).then( resp => {
                if ( resp.status === 200 ) {
                    msg.success();
                    setHasImage(false);
                } else {
                    return resp.json();
                }
            }).then( json=>{
               msg.dismiss();
               if ( json ) {
                   Informationals.makeDanger("Error uploading logo", json.message, 3000).show();
               }
            });
     }});
}

function setHasImage( hasImage ) {
    if ( hasImage ) {
        $("#noImage").hide();
        $("#imageDiv").show();
        $("#deleteImageBtn").show();
        document.getElementById("imgLogo").src=jsRoutes.controllers.CustomizationCtrl.getServerLogo().url + "?" + Math.ceil(Math.random()*2048);
    } else {
        $("#noImage").show();
        $("#imageDiv").hide();
        $("#deleteImageBtn").hide();
    }
}

function setup(){
    console.log("Setting up");
}

function saveStyling() {
    const css = collectCss();

    const call = jsRoutes.controllers.CustomizationCtrl.apiSetCustomization("BRANDING_CSS");
    const msg = Informationals.showBackgroundProcess("Updating style");
    $.ajax(call.url, {
        type: call.method,
        data: css,
        dataType: "text",
        contentType: "text/plain; charset=utf-8"
    }).done(function (data, status, jqXhr) {
        msg.success();
        Informationals.makeSuccess( "Styling updated", 2000 );
    }).fail( function(jXHR, status, message){
        msg.dismiss();
        Informationals.makeDanger("Error saving style data", message + " (" + status + ")" );
        console.log(jXHR);
        console.log(status);
        console.log(message);
    });
}
