var functions = require('firebase-functions');
const Speech = require('@google-cloud/speech');
const speech = Speech({keyFilename: "service-account-credentials.json"});
const Translate = require('@google-cloud/translate');
const translate = Translate({keyFilename: "service-account-credentials.json"});

function getLanguageWithoutLocale(languageCode) {
    if (languageCode.indexOf("-") >= 0) {
        return languageCode.substring(0, languageCode.indexOf("-"));
    }
    return languageCode;
}

// When a node is written under /uploads, run this function
exports.onUpload = functions.database
    .ref("/uploads/{uploadId}")
    .onWrite((event) => {
        // get metadata about the audio file from the database node
        var data = event.data.val();
        var language = data.language ? data.language : "en";
        var encoding = data.encoding ? data.encoding : "FLAC";
        var sampleRate = data.sampleRate ? data.sampleRate : 16000;

        // Send the file to the Cloud Speech API to get the transcript
        return speech.recognize(`gs://mimming-babelfire.appspot.com/${data.fullPath}`, {
            encoding: encoding,
            sampleRate: sampleRate,
            languageCode: language
        })// Write the resulting transcript to the database (in a different node)
            .then((results) => {
                var transcript = results[0];
                return event.data.adminRef.root
                    .child("transcripts").child(event.params.uploadId)
                    .set({text: transcript, language: language});
            });
    });


// When a node is written under /transcripts, run this function
exports.onTranscript = functions.database
    .ref("/transcripts/{transcriptId}")
    .onWrite((event) => {
        // get the text we need to translate and the language it is in
        let value = event.data.val();
        let text = value.text ? value.text : value;
        let from = value.language ? getLanguageWithoutLocale(value.language) : "en";

        // all supported languages: https://cloud.google.com/translate/docs/languages
        let languages = ["en", "es", "pt", "de", "ja", "hi", "nl", "fr", "pl"];
        let promises = languages.map(to => {
            console.log(`translating from '${from}' to '${to}', text '${text}'`);
            // call the Google Cloud Platform Translate API
            return translate.translate(text, {
                from: from,
                to: to
            }).then(result => {
                // write the translation to the database
                translation = result[0];
                return event.data.adminRef.root
                    .child("translations").child(event.params.transcriptId).child(to)
                    .set({text: translation, language: to});
            });
        });
        return Promise.all(promises);
    });
