// Copyright 2017 Google Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.


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

exports.onUpload = functions.database
    .ref("/uploads/{uploadId}")
    .onWrite((event) => {
        var data = event.data.val();
        var language = data.language ? data.language : "en";
        var encoding = data.encoding ? data.encoding : "FLAC";
        var sampleRate = data.sampleRate ? data.sampleRate : 16000;

        return speech.recognize(`gs://mimming-babelfire.appspot.com/${data.fullPath}`, {
            encoding: encoding,
            sampleRate: sampleRate,
            languageCode: language
        }).then((results) => {
            var transcript = results[0];
            return event.data.adminRef.root
                .child("transcripts").child(event.params.uploadId)
                .set({text: transcript, language: language});
        });
    });

exports.onTranscript = functions.database
    .ref("/transcripts/{transcriptId}")
    .onWrite((event) => {
        var value = event.data.val();
        var text = value.text ? value.text : value;
        var languages = ["en", "es", "pt", "de", "ja", "hi", "nl"];
        // all supported languages: https://cloud.google.com/translate/docs/languages
        var from = value.language ? getLanguageWithoutLocale(value.language) : "en";
        var promises = languages.map(to => {
            console.log(`translating from '${from}' to '${to}', text '${text}'`);
            // call the Google Cloud Platform Translate API
            if (from == to) {
                return event.data.adminRef.root
                    .child("translations").child(event.params.transcriptId).child(to)
                    .set({text: text, language: from});
            } else {
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
            }
        });
        return Promise.all(promises);
    });
