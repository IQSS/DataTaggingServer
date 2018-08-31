# How to request an interview

1. prepare the interview data, as in the file `InterviewRequest.json`.
2. `POST` the json to `api/1/interviewRequest/model-id/version-id`, capture the `303 See Other` response data. Note that `model-id` is a string, and `version-id` is an integer.

  curl -i -X POST --data-binary @InterviewRequest.json http://localhost:9000/api/1/interviewRequest/cats-dogs/1

3. Sample response:

  HTTP/1.1 303 See Other
  Location: /requestedInterview/376ac6e3-c434-4120-a836-ac230a8c3f55
  Date: Mon, 27 Aug 2018 18:32:53 GMT
  Content-Length: 0

4. Redirect the user to the `Location` in the response (plus the PolicyModels server base URL).
5. Await a `POST` of the interview results into the `callbackURL` provided in the json. This `POST` can have two forms:

  * When accepting:

    ```json
    {
      "status": "accept",
      "value":{
        policy model value goes here
      }
    }
    ```

  * When rejecting:

    ```json
    {
        "status":"reject",
        "reason":"rejection reason string"
    }
    ```
