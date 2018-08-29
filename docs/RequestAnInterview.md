# How to request an interview

1. prepare the interview data, as in the file `InterviewRequest.json`.
2. `POST` the json to `api/1/interviewRequest/cats-dogs/1`, capture the `303 See Other` response data.

  curl -i -X POST --data-binary @InterviewRequest.json http://localhost:9000/api/1/interviewRequest/cats-dogs/1
  HTTP/1.1 303 See Other
  Location: /requestedInterview/376ac6e3-c434-4120-a836-ac230a8c3f55
  Date: Mon, 27 Aug 2018 18:32:53 GMT
  Content-Length: 0

3. Redirect the user to the `Location` in the response
4. Await a `POST` of the interview results into the `callbackURL` provided in the json.