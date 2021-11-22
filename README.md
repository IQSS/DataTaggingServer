# PolicyModels Web Application

A web application for developing and conducting PolicyModels interviews. This system allows model developers to make models available online (either publicly or privately), to receive feedback about them.

This server supports interview localizations. If a model contains multiple localizations, users can switch between languages as they go.

After each PolicyModels interview, the system displays the interview results in a user-friendly manner. Users can also print interview transcripts.

See demo server [here](https://iqss-datatags-dev.rc.fas.harvard.edu/models/usdd/start).

## Customizations

Customizations are supported at three levels: Model version, Model, and Global.

### Model Version

Server admins can customize model value display, enable commenting, and make a version public, available through a link, or private.

### Model

Server admins can specify whether interviewees need to affirm their answers, whether they can add notes, and whether interview statistics are saved.

### Global (settings for the entire server)

Server admins can:

* Edit the home (index), models, and about pages.
* Edit additional textual values, such as the server's name, limited liability statement, etc.
* Edit the server's branding, including logo upload, main color selection, and custom CSS
* Add analytics code.

## Integration with Other Systems

This server can conduct interviews on behalf of other systems, via a REST API. When using this feature, the client system receives the interview results, and can specify where to send the user to after the interview is done. From a user's point of view, the process is seamless, as the transition between systems can be done without requiring user any action.

System interaction is as follows:
1. Client system requests an interview from PoMo Server (this app). This request includes a URL to `POST` the results back to, when the interview is done.
2. PoMo Server responds with a URL for starting the interview.
3. The client system redirects the user to said URL.
4. PoMo server conducts the interview
5. PoMo server posts the results to the URL sent by the client system at stage 1.
6. Client system responds with a new URL to send the user to.
7. PoMo server redirects the user to said URL.

### API Endpoints

`GET  /api/1/models/`

Lists all models that have public runnable versions.

`GET  /api/1/models/:modelId/`

Lists all public runnable versions of model `:modelId`.

`GET  /api/1/models/:modelId/:ver`

Returns model version metadata for model `:modelId`.

`POST /api/1/models/:modelId/requests`

Requests an interview with the latest public version of model `:modelId`. See payload below.

`POST /api/1/models/:modelId/:ver/requests`

Requests an interview with the version `:ver` of model `:modelId`. See payload below.

Interview request payload:

```json
{
    "callbackURL"       :String,
    ["localization"      :String,]
    ["message"           :String,]
    "returnButtonTitle" :String,
    "returnButtonText"  :String
}
```

Where: 

* `callbackURL`: The URL to POST the results to.
* `localization`: _Optional._ Name of the localization to use.
* `message`: _Optional._ Message to display before the interview begins.
* `returnButtonTitle`: Text to appear _above_ the button that sends the results to the client system.
* `returnButtonText`: Text to appear _on_ the button that sends the results to the client system.


**Sample Client**

An example client application is [available in this repository](SamplePoMoSClientApp).

## Note

> This server library uses the core [PolicyModels language library](https://github.com/IQSS/DataTaggingLibrary).
>
>For more information, visit [datatags.org](http://datatags.org).

## Initial Setup

* Configuration: see conf/application.conf
* Add a user via API from localhost (useful for adding first user):

    `echo '{"username": "admin", "password":"pass"}' | http POST localhost:9000/admin/api/users/`
