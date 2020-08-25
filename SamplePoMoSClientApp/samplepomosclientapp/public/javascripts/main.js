const cns = document.getElementById("console")
let consoleCleaned = false;


function getServerUrl() {
    return document.getElementById("fldServerAddress").value;
}

function consoleClean() {
    while ( cns.childElementCount > 0 ) {
        cns.firstChild.remove();
    }
    consoleCleaned = true;
}

function consoleAddInput( str ) {
    cns.innerHTML = cns.innerHTML + "<div class='input'>" + str + "</div>";
}

function consoleAddOutput( str ) {
    cns.innerHTML = cns.innerHTML + "<div class='output'>" + str + "</div>";
}

function listModels() {
    performGet(getServerUrl() + "/api/1/models/");
}

function listVersions() {
    performGet(getServerUrl() + "/api/1/models/" + document.getElementById("livModelId").value + "/");
}

function versionInfo() {
    performGet(getServerUrl() + "/api/1/models/" + document.getElementById("mviModelId").value + "/" + document.getElementById("mviVersion").value);
}

function requestInterviewSpecific() {
    requestInterview(getServerUrl() + "/api/1/models/" +
            document.getElementById("risModelId").value + "/" +
            document.getElementById("risModelVersion").value + "/requests"
    );
}

function requestInterviewNewest() {
    requestInterview(getServerUrl() + "/api/1/models/" +
                      document.getElementById("rinModelId").value + "/requests");
}

function requestInterview( endpoint ){
    const payload = {
        "callbackURL": document.getElementById("fldCallbackURL").value,
        "returnButtonTitle": document.getElementById("fldRbTitle").value,
        "returnButtonText":document.getElementById("fldRbText").value
    }
    const loc = document.getElementById("fldLocalization").value.trim();
    if ( loc.length > 0 ) {
        payload.localization = loc;
    }
    const msg = document.getElementById("fldMessage").value.trim();
    if ( msg.length > 0 ) {
        payload.message = msg;
    }

    consoleAddInput("/!\\ performed from the SERVER SIDE, so PolicyModels Server can post the results back" );
    consoleAddInput("POST " + endpoint );
    consoleAddInput(JSON.stringify(payload, null, 2));

    // Actual POST is done by the SERVER, no the client, so we add
    payload.endpoint = endpoint;

    fetch("/api/requestInterview", {
        method: "POST",
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    }).then( res=>{
        if ( res.ok ) {
            res.json().then( jsn => {
                consoleAddOutput("/!\\ HTTP response from the PolicyModels server to the client server" );
                consoleAddOutput("201 " + jsn.interviewUrl );
                const interviewUrl = getServerUrl() + jsn.interviewUrl;
                const interviewAnchor = document.getElementById("interviewReadyHref");
                interviewAnchor.innerText = interviewUrl;
                interviewAnchor.setAttribute("href", interviewUrl);
                document.getElementById("interviewReady").style.display="block";
            });
        } else {
            printError(res);
        }
    }).catch( function(exp){
        consoleAddOutput(exp);
    });
}

function performGet( endpoint ) {
    consoleAddInput("GET " + endpoint );
    fetch( endpoint, {
        method: "GET"
    }).then( function(response){
        if ( response.ok ) {
            response.json().then( function(data){
                consoleAddOutput( JSON.stringify(data, null, 2) );
            });
        } else {
           printError(response);
        }
    }).catch( function(exp){
        consoleAddOutput(exp);
    });
}

function printError( response ) {
    consoleAddOutput( "Error " + response.status );
    consoleAddOutput( " " + response.statusText );
    response.text().then( t => consoleAddOutput(t) );
}

document.querySelector("#fldCallbackURL").value = String(window.location) + "postback/123DEF";
