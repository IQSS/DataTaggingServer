
// update the server enpoint on the page
function updateEndpointUrl() {
    var url = document.querySelector("#fldServerAddress").value +
              "/api/1/interviewRequest/" +
              document.querySelector("#fldModelId").value +
              "/" +
              document.querySelector("#fldModelVersion").value;

    document.querySelector("#endpointUrl").innerHTML = url;
}

function lowercaseFirst( string ) {
    return string.substring(0,1).toLowerCase() + string.substring(1);
}

function updateJson() {
    let obj = {};
    ["Title", "Message", "ReturnButtonTitle", "ReturnButtonText", "CallbackURL"].forEach( idSeed => {
        let value =  document.querySelector("#fld" + idSeed).value
        obj[lowercaseFirst(idSeed)] = value;
    });

    let jsonNice = JSON.stringify(obj).replace(/,/g, ",\n").replace(/{/, "{\n").replace(/}/,"\n}");
    document.querySelector("#payload").innerHTML=jsonNice;
}

["ServerAddress", "ModelId", "ModelVersion"].forEach( idSeed => {
   let input = document.querySelector("#fld" + idSeed);
   input.onchange = updateEndpointUrl;
   input.onkeyup = updateEndpointUrl;
});

["Title", "Message", "ReturnButtonTitle",
    "ReturnButtonText", "CallbackURL"].forEach( idSeed => {
    let input = document.querySelector("#fld" + idSeed);
    input.onchange = updateJson;
    input.onkeyup = updateJson;
} );

updateEndpointUrl();
updateJson();
document.querySelector("#fldCallbackURL").value = String(window.location) + "postback/123DEF";