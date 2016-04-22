var watson = require('watson-developer-cloud');
var fs = require('fs');
var stream = require('stream');

function isValidEncoding(encoding) {
  return encoding === 'ascii' ||
    encoding === 'utf8' ||
    encoding === 'utf16le' ||
    encoding === 'ucs2' ||
    encoding === 'base64' ||
    encoding === 'binary' ||
    encoding === 'hex';
}

/**
 * Transcribes speech from various languages and audio formats to text
 * See https://www.ibm.com/smarterplanet/us/en/ibmwatson/developercloud/speech-to-text/api/v1/#recognize_audio_websockets
 *
 * @param content_type The MIME type of the audio. Required.
 * @param model The identifier of the model to be used for the recognition request.
 * @param continuous Indicates whether multiple final results that represent consecutive phrases separated by long pauses are returned.
 * @param inactivity_timeout The time in seconds after which, if only silence (no speech) is detected in submitted audio, the connection is closed. The default is 30 seconds.
 * @param interim_results	Indicates whether the service is to return interim results.
 * @param keywords A list of keywords to spot in the audio.
 * @param keywords_threshold A confidence value that is the lower bound for spotting a keyword.
 * @param max_alternatives The maximum number of alternative transcripts to be returned.
 * @param word_alternatives_threshold A confidence value that is the lower bound for identifying a hypothesis as a possible word alternative.
 * @param word_confidence Indicates whether a confidence measure in the range of 0 to 1 is to be returned for each word.
 * @param timestamps Indicates whether time alignment is returned for each word.
 * @param X-Watson-Learning-Opt-Out	Indicates whether to opt out of data collection for the call.
 * @param watson-token Provides an authentication token for the service as an alternative to providing service credentials.
 * @param encoding The encoding of the speech binary data. Required.
 * @param payload The encoded data to turn into text. Required.
 * @param username The Watson service username.
 * @param password The Watson service password.
 *
 * @return {
 *  "data": "Final results for the request",
 *  "results": "Interim results for the request"
 *  "error": "Errors for the connection"
 * }
 */
function main(params) {
  var payload = params.payload;
  var encoding = params.encoding;
  var username = params.username;
  var password = params.password;

  console.log('params:', params);

  if (!payload) {
    return whisk.done({
      'error': 'No payload specified.'
    });
  } else if (!isValidEncoding(encoding)) {
    return whisk.done({
      'error': 'Not a valid encoding.'
    });
  }

  var response = {};
  var speechToText = watson.speech_to_text({
    username: username,
    password: password,
    version: 'v1'
  });

  // Create the stream.
  var recognizeStream = speechToText.createRecognizeStream({
    content_type: params.content_type,
    model: params.model,
    continuous: params.continuous,
    inactivity_timeout: params.inactivity_timeout,
    interim_results: params.interim_results,
    keywords: params.keywords,
    max_alternatives: params.max_alternatives,
    word_alternatives_threshold: params.word_alternatives_threshold,
    word_confidence: params.word_confidence,
    timestamps: params.timestamps,
    'X-Watson-Learning-Opt-Out': params['X-Watson-Learning-Opt-Out'],
    'watson-token': params['watson-token']
  });

  // Pipe in some audio.
  var b = Buffer(payload, encoding);
  var s = new stream.Readable();
  s._read = function noop() {}; // Needed to escape not implemented exception
  s.push(b);
  s.pipe(recognizeStream);
  s.push(null);

  // Pipe out the transcription.
  recognizeStream.pipe(fs.createWriteStream('output'));

  // Get strings instead of buffers from `data` events.
  recognizeStream.setEncoding('utf8');

  // Listen for 'data' events for only the final results.
  // Listen for 'results' events to get interim results.
  ['data', 'results', 'error', 'connection-close'].forEach(function (name) {
    recognizeStream.on(name, function (event_) {
      if (name === 'data') {
        response.data = event_;
        whisk.done(response);
      } else if (name === 'results' && params.interim_results) {
        if (!response.results)
          response.results = [];
        response.results.push(event_);
      } else if (name === 'error' || name === 'connection-close') {
        response.error = event_ && typeof(event_.toString) === 'function' ?
          event_.toString() : 'Watson API failed';
        whisk.done(response);
      }
    });
  });

  return whisk.async();
}

